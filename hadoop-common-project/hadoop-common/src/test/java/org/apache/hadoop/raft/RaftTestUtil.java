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
package org.apache.hadoop.raft;

import org.apache.hadoop.raft.protocol.Message;
import org.apache.hadoop.raft.server.RaftConstants;
import org.apache.hadoop.raft.server.RaftServer;
import org.apache.hadoop.raft.server.protocol.Entry;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.test.MoreAsserts.assertEquals;
import static org.junit.Assert.assertTrue;

public class RaftTestUtil {
  static final Logger LOG = LoggerFactory.getLogger(RaftTestUtil.class);

  public static RaftServer waitForLeader(MiniRaftCluster cluster)
      throws InterruptedException {
    LOG.info(cluster.printServers());
    Thread.sleep(RaftConstants.ELECTION_TIMEOUT_MAX_MS + 100);
    LOG.info(cluster.printServers());
    return cluster.getLeader();
  }

  public static RaftServer waitForLeader(MiniRaftCluster cluster,
      String leaderId) throws InterruptedException {
    LOG.info(cluster.printServers());
    LOG.info("target leader = " + leaderId);
    while (!cluster.tryEnforceLeader(leaderId)) {
      RaftServer currLeader = cluster.getLeader();
      if (currLeader != null) {
        LOG.debug("try enforce leader, new leader = {}", currLeader.getId());
      } else {
        LOG.debug("no leader for this round");
      }
    }
    RaftServer leader = cluster.getLeader();
    LOG.info(cluster.printServers());
    return leader;
  }

  public static String waitAndKillLeader(MiniRaftCluster cluster,
      boolean expectLeader) throws InterruptedException {
    final RaftServer leader = waitForLeader(cluster);
    if (!expectLeader) {
      Assert.assertNull(leader);
    } else {
      Assert.assertNotNull(leader);
      LOG.info("killing leader = " + leader);
      cluster.killServer(leader.getId());
    }
    return leader != null ? leader.getId() : null;
  }

  static void assertLogEntriesContains(Entry[] entries,
      SimpleMessage... expectedMessages) {
    int idxEntries = 0;
    int idxExpected = 0;
    while (idxEntries < entries.length
        && idxExpected < expectedMessages.length) {
      if (expectedMessages[idxExpected].equals(
          entries[idxEntries].getMessage())) {
        ++idxExpected;
      }
      ++idxEntries;
    }
    assertTrue("Total number of matched records: " + idxExpected,
        expectedMessages.length == idxExpected);
  }

  static void assertLogEntries(Entry[] entries, long startIndex,
      long expertedTerm, SimpleMessage... expectedMessages) {
    Assert.assertEquals(expectedMessages.length, entries.length);
    for(int i = 0; i < entries.length; i++) {
      final Entry e = entries[i];
      Assert.assertEquals(expertedTerm, e.getTerm());
      Assert.assertEquals(startIndex + i, e.getIndex());
      Assert.assertEquals(expectedMessages[i], e.getMessage());
    }
  }

  public static class SimpleMessage implements Message {
    final String messageId;

    public SimpleMessage(final String messageId) {
      this.messageId = messageId;
    }

    @Override
    public String toString() {
      return messageId;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj == null || !(obj instanceof SimpleMessage)) {
        return false;
      } else {
        final SimpleMessage that = (SimpleMessage)obj;
        return this.messageId.equals(that.messageId);
      }
    }
  }
}