/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.raft.server;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.Time;
import org.apache.raft.proto.RaftProtos.AppendEntriesReplyProto;
import org.apache.raft.proto.RaftProtos.AppendEntriesRequestProto;
import org.apache.raft.proto.RaftProtos.FileChunkProto;
import org.apache.raft.proto.RaftProtos.InstallSnapshotReplyProto;
import org.apache.raft.proto.RaftProtos.InstallSnapshotRequestProto;
import org.apache.raft.proto.RaftProtos.InstallSnapshotResult;
import org.apache.raft.proto.RaftProtos.LogEntryProto;
import org.apache.raft.server.LeaderState.StateUpdateEventType;
import org.apache.raft.server.protocol.ServerProtoUtils;
import org.apache.raft.server.protocol.TermIndex;
import org.apache.raft.server.storage.FileInfo;
import org.apache.raft.server.storage.RaftLog;
import org.apache.raft.statemachine.SnapshotInfo;
import org.apache.raft.util.ProtoUtils;
import org.slf4j.Logger;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.apache.raft.server.RaftServerConfigKeys.RAFT_SERVER_LOG_APPENDER_BATCH_ENABLED_DEFAULT;
import static org.apache.raft.server.RaftServerConfigKeys.RAFT_SERVER_LOG_APPENDER_BATCH_ENABLED_KEY;
import static org.apache.raft.server.RaftServerConfigKeys.RAFT_SERVER_LOG_APPENDER_BUFFER_CAPACITY_DEFAULT;
import static org.apache.raft.server.RaftServerConfigKeys.RAFT_SERVER_LOG_APPENDER_BUFFER_CAPACITY_KEY;
import static org.apache.raft.server.RaftServerConstants.INVALID_LOG_INDEX;

/**
 * A daemon thread appending log entries to a follower peer.
 */
public class LogAppender extends Daemon {
  public static final Logger LOG = RaftServer.LOG;

  protected final RaftServer server;
  private final LeaderState leaderState;
  protected final RaftLog raftLog;
  protected final FollowerInfo follower;
  private final int maxBufferEntryNum;
  private final boolean batchSending;
  private final LogEntryBuffer buffer;
  private final long leaderTerm;

  private volatile boolean sending = true;

  public LogAppender(RaftServer server, LeaderState leaderState, FollowerInfo f) {
    this.follower = f;
    this.server = server;
    this.leaderState = leaderState;
    this.raftLog = server.getState().getLog();
    this.maxBufferEntryNum = server.getProperties().getInt(
        RAFT_SERVER_LOG_APPENDER_BUFFER_CAPACITY_KEY,
        RAFT_SERVER_LOG_APPENDER_BUFFER_CAPACITY_DEFAULT);
    this.batchSending = server.getProperties().getBoolean(
        RAFT_SERVER_LOG_APPENDER_BATCH_ENABLED_KEY,
        RAFT_SERVER_LOG_APPENDER_BATCH_ENABLED_DEFAULT);
    this.buffer = new LogEntryBuffer();
    this.leaderTerm = server.getState().getCurrentTerm();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(" + server.getId() + " -> " +
        follower.getPeer().getId() + ")";
  }

  @Override
  public void run() {
    try {
      checkAndSendAppendEntries();
    } catch (InterruptedException | InterruptedIOException e) {
      LOG.info(this + " was interrupted: " + e);
    }
  }

  protected boolean isAppenderRunning() {
    return sending;
  }

  public void stopSender() {
    this.sending = false;
  }

  public FollowerInfo getFollower() {
    return follower;
  }

  /**
   * A buffer for log entries with size limitation.
   */
  private class LogEntryBuffer {
    private final List<LogEntryProto> buf = new ArrayList<>(maxBufferEntryNum);

    void addEntries(LogEntryProto... entries) {
      Collections.addAll(buf, entries);
    }

    boolean isFull() {
      return buf.size() >= maxBufferEntryNum;
    }

    boolean isEmpty() {
      return buf.isEmpty();
    }

    int getRemainingSlots() {
      return maxBufferEntryNum - buf.size();
    }

    AppendEntriesRequestProto getAppendRequest(TermIndex previous) {
      final AppendEntriesRequestProto request = server
          .createAppendEntriesRequest(leaderTerm, follower.getPeer().getId(),
              previous, buf, !follower.isAttendingVote());
      buf.clear();
      return request;
    }

    int getPendingEntryNum() {
      return buf.size();
    }
  }

  private TermIndex getPrevious() {
    TermIndex previous = ServerProtoUtils.toTermIndex(
        raftLog.get(follower.getNextIndex() - 1));
    if (previous == null) {
      // if previous is null, nextIndex must be equal to the log start
      // index (otherwise we will install snapshot).
      Preconditions.checkState(follower.getNextIndex() == raftLog.getStartIndex(),
          "follower's next index %s, local log start index %s",
          follower.getNextIndex(), raftLog.getStartIndex());
      SnapshotInfo snapshot = server.getState().getLatestSnapshot();
      previous = snapshot == null ? null : snapshot.getTermIndex();
    }
    return previous;
  }

  protected AppendEntriesRequestProto createRequest() {
    final TermIndex previous = getPrevious();
    final long leaderNext = raftLog.getNextIndex();
    final long next = follower.getNextIndex() + buffer.getPendingEntryNum();
    boolean toSend = false;
    if (leaderNext > next) {
      int num = Math.min(buffer.getRemainingSlots(), (int) (leaderNext - next));
      buffer.addEntries(raftLog.getEntries(next, next + num));
      if (buffer.isFull() || !batchSending) {
        // buffer is full or batch sending is disabled, send out a request
        toSend = true;
      }
    } else if (!buffer.isEmpty()) {
      // no new entries, then send out the entries in the buffer
      toSend = true;
    }
    if (toSend || shouldHeartbeat()) {
      return buffer.getAppendRequest(previous);
    }
    return null;
  }

  /** Send an appendEntries RPC; retry indefinitely. */
  private AppendEntriesReplyProto sendAppendEntriesWithRetries()
      throws InterruptedException, InterruptedIOException {
    int retry = 0;
    AppendEntriesRequestProto request = null;
    while (isAppenderRunning()) { // keep retrying for IOException
      try {
        if (request == null || request.getEntriesCount() == 0) {
          request = createRequest();
        }

        if (request == null) {
          LOG.trace("{} need not send AppendEntries now." +
              " Wait for more entries.", server.getId());
          return null;
        } else if (!isAppenderRunning()) {
          LOG.debug("LogAppender {} has been stopped. Skip the request.", this);
          return null;
        }

        follower.updateLastRpcSendTime(Time.monotonicNow());
        final AppendEntriesReplyProto r = server.getServerRpc()
            .sendAppendEntries(request);
        follower.updateLastRpcResponseTime(Time.monotonicNow());

        return r;
      } catch (InterruptedIOException iioe) {
        throw iioe;
      } catch (IOException ioe) {
        LOG.debug(this + ": failed to send appendEntries; retry " + retry++, ioe);
      }
      if (isAppenderRunning()) {
        Thread.sleep(leaderState.getSyncInterval());
      }
    }
    return null;
  }

  protected class SnapshotRequestIter
      implements Iterable<InstallSnapshotRequestProto> {
    private final SnapshotInfo snapshot;
    private final List<FileInfo> files;
    private FileInputStream in;
    private int fileIndex = 0;

    private FileInfo currentFileInfo;
    private byte[] currentBuf;
    private long currentFileSize;
    private long currentOffset = 0;
    private int chunkIndex = 0;

    private final String requestId;
    private int requestIndex = 0;

    public SnapshotRequestIter(SnapshotInfo snapshot, String requestId)
        throws IOException {
      this.snapshot = snapshot;
      this.requestId = requestId;
      this.files = snapshot.getFiles();
      if (files.size() > 0) {
        startReadFile();
      }
    }

    private void startReadFile() throws IOException {
      currentFileInfo = files.get(fileIndex);
      File snapshotFile = currentFileInfo.getPath().toFile();
      currentFileSize = snapshotFile.length();
      final int bufLength =
          (int) Math.min(leaderState.getSnapshotChunkMaxSize(), currentFileSize);
      currentBuf = new byte[bufLength];
      currentOffset = 0;
      chunkIndex = 0;
      in = new FileInputStream(snapshotFile);
    }

    @Override
    public Iterator<InstallSnapshotRequestProto> iterator() {
      return new Iterator<InstallSnapshotRequestProto>() {
        @Override
        public boolean hasNext() {
          return fileIndex < files.size();
        }

        @Override
        public InstallSnapshotRequestProto next() {
          if (fileIndex >= files.size()) {
            throw new NoSuchElementException();
          }
          int targetLength = (int) Math.min(currentFileSize - currentOffset,
              leaderState.getSnapshotChunkMaxSize());
          FileChunkProto chunk;
          try {
            chunk = readFileChunk(currentFileInfo, in, currentBuf,
                targetLength, currentOffset, chunkIndex);
            boolean done = (fileIndex == files.size() - 1) &&
                chunk.getDone();
            InstallSnapshotRequestProto request =
                server.createInstallSnapshotRequest(follower.getPeer().getId(),
                    requestId, requestIndex++, snapshot,
                    Lists.newArrayList(chunk), done);
            currentOffset += targetLength;
            chunkIndex++;

            if (currentOffset >= currentFileSize) {
              in.close();
              fileIndex++;
              if (fileIndex < files.size()) {
                startReadFile();
              }
            }

            return request;
          } catch (IOException e) {
            if (in != null) {
              try {
                in.close();
              } catch (IOException ignored) {
              }
            }
            LOG.warn("Got exception when preparing InstallSnapshot request", e);
            throw new RuntimeException(e);
          }
        }
      };
    }
  }

  private FileChunkProto readFileChunk(FileInfo fileInfo,
      FileInputStream in, byte[] buf, int length, long offset, int chunkIndex)
      throws IOException {
    FileChunkProto.Builder builder = FileChunkProto.newBuilder()
        .setOffset(offset).setChunkIndex(chunkIndex);
    IOUtils.readFully(in, buf, 0, length);
    Path relativePath = server.getState().getStorage().getStorageDir()
        .relativizeToRoot(fileInfo.getPath());
    builder.setFilename(relativePath.toString());
    builder.setDone(offset + length == fileInfo.getFileSize());
    builder.setFileDigest(
        ByteString.copyFrom(fileInfo.getFileDigest().getDigest()));
    builder.setData(ByteString.copyFrom(buf, 0, length));
    return builder.build();
  }

  private InstallSnapshotReplyProto installSnapshot(SnapshotInfo snapshot)
      throws InterruptedException, InterruptedIOException {
    String requestId = UUID.randomUUID().toString();
    InstallSnapshotReplyProto reply = null;
    try {
      for (InstallSnapshotRequestProto request :
          new SnapshotRequestIter(snapshot, requestId)) {
        follower.updateLastRpcSendTime(Time.monotonicNow());
        reply = server.getServerRpc().sendInstallSnapshot(request);
        follower.updateLastRpcResponseTime(Time.monotonicNow());

        if (!reply.getServerReply().getSuccess()) {
          return reply;
        }
      }
    } catch (InterruptedIOException iioe) {
      throw iioe;
    } catch (Exception ioe) {
      LOG.warn(this + ": failed to install SnapshotInfo " + snapshot.getFiles(),
          ioe);
      return null;
    }

    if (reply != null) {
      follower.updateMatchIndex(snapshot.getTermIndex().getIndex());
      follower.updateNextIndex(snapshot.getTermIndex().getIndex() + 1);
      LOG.info("{}: install snapshot-{} successfully on follower {}",
          server.getId(), snapshot.getTermIndex().getIndex(), follower.getPeer());
    }
    return reply;
  }

  protected SnapshotInfo shouldInstallSnapshot() {
    final long logStartIndex = raftLog.getStartIndex();
    // we should install snapshot if the follower needs to catch up and:
    // 1. there is no local log entry but there is snapshot
    // 2. or the follower's next index is smaller than the log start index
    if (follower.getNextIndex() < raftLog.getNextIndex()) {
      SnapshotInfo snapshot = server.getState().getLatestSnapshot();
      if (follower.getNextIndex() < logStartIndex ||
          (logStartIndex == INVALID_LOG_INDEX && snapshot != null)) {
        return snapshot;
      }
    }
    return null;
  }

  /** Check and send appendEntries RPC */
  private void checkAndSendAppendEntries()
      throws InterruptedException, InterruptedIOException {
    while (isAppenderRunning()) {
      if (shouldSendRequest()) {
        SnapshotInfo snapshot = shouldInstallSnapshot();
        if (snapshot != null) {
          LOG.info("{}: follower {}'s next index is {}," +
              " log's start index is {}, need to install snapshot",
              server.getId(), follower.getPeer(), follower.getNextIndex(),
              raftLog.getStartIndex());

          final InstallSnapshotReplyProto r = installSnapshot(snapshot);
          if (r != null && r.getResult() == InstallSnapshotResult.NOT_LEADER) {
            checkResponseTerm(r.getTerm());
          } // otherwise if r is null, retry the snapshot installation
        } else {
          final AppendEntriesReplyProto r = sendAppendEntriesWithRetries();
          if (r != null) {
            handleReply(r);
          }
        }
      }
      if (isAppenderRunning() && !shouldAppendEntries(
          follower.getNextIndex() + buffer.getPendingEntryNum())) {
        final long waitTime = getHeartbeatRemainingTime(
            follower.getLastRpcTime());
        if (waitTime > 0) {
          synchronized (this) {
            wait(waitTime);
          }
        }
      }
    }
  }

  private void handleReply(AppendEntriesReplyProto reply) {
    if (reply != null) {
      switch (reply.getResult()) {
        case SUCCESS:
          final long oldNextIndex = follower.getNextIndex();
          final long nextIndex = reply.getNextIndex();
          if (nextIndex < oldNextIndex) {
            throw new IllegalStateException("nextIndex=" + nextIndex
                + " < oldNextIndex=" + oldNextIndex
                + ", reply=" + ProtoUtils.toString(reply));
          }

          if (nextIndex > oldNextIndex) {
            follower.updateMatchIndex(nextIndex - 1);
            follower.updateNextIndex(nextIndex);
            submitEventOnSuccessAppend();
          }
          break;
        case NOT_LEADER:
          // check if should step down
          checkResponseTerm(reply.getTerm());
          break;
        case INCONSISTENCY:
          follower.decreaseNextIndex(reply.getNextIndex());
          break;
        case UNRECOGNIZED:
          LOG.warn("{} received UNRECOGNIZED AppendResult from {}",
              server.getId(), follower.getPeer().getId());
          break;
      }
    }
  }

  protected void submitEventOnSuccessAppend() {
    LeaderState.StateUpdateEvent e = follower.isAttendingVote() ?
        LeaderState.UPDATE_COMMIT_EVENT :
        LeaderState.STAGING_PROGRESS_EVENT;
    leaderState.submitUpdateStateEvent(e);
  }

  public synchronized void notifyAppend() {
    this.notify();
  }

  /** Should the leader send appendEntries RPC to this follower? */
  protected boolean shouldSendRequest() {
    return shouldAppendEntries(follower.getNextIndex()) || shouldHeartbeat();
  }

  private boolean shouldAppendEntries(long followerIndex) {
    return followerIndex < raftLog.getNextIndex();
  }

  private boolean shouldHeartbeat() {
    return getHeartbeatRemainingTime(follower.getLastRpcTime()) <= 0;
  }

  /**
   * @return the time in milliseconds that the leader should send a heartbeat.
   */
  protected long getHeartbeatRemainingTime(long lastTime) {
    return lastTime + server.minTimeout / 2 - Time.monotonicNow();
  }

  protected void checkResponseTerm(long responseTerm) {
    synchronized (server) {
      if (isAppenderRunning() && follower.isAttendingVote()
          && responseTerm > leaderState.getCurrentTerm()) {
        leaderState.submitUpdateStateEvent(
            new LeaderState.StateUpdateEvent(StateUpdateEventType.STEPDOWN,
                responseTerm));
      }
    }
  }
}