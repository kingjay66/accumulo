/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.test.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.TabletLocationState;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloConfigImpl;
import org.apache.accumulo.server.manager.state.MetaDataTableScanner;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;

public class CompactLocationModeIT extends ConfigurableMacBase {

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(2);
  }

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration fsConf) {
    cfg.setProperty(Property.TSERV_LAST_LOCATION_MODE, "compaction");
  }

  @Test
  public void test() throws Exception {
    try (AccumuloClient c =
        getCluster().createAccumuloClient("root", new PasswordToken(ROOT_PASSWORD))) {
      String tableName = super.getUniqueNames(1)[0];
      c.tableOperations().create(tableName);
      String tableId = c.tableOperations().tableIdMap().get(tableName);
      // wait for the table to be online
      TabletLocationState newTablet;
      do {
        UtilWaitThread.sleep(250);
        newTablet = getTabletLocationState(c, tableId);
      } while (newTablet.current == null);
      assertNull(newTablet.last);
      assertNull(newTablet.future);

      // put something in it
      try (BatchWriter bw = c.createBatchWriter(tableName)) {
        Mutation m = new Mutation("a");
        m.put("b", "c", "d");
        bw.addMutation(m);
      }
      // assert that the default mode is "compact"
      assertEquals("compaction", c.instanceOperations().getSystemConfiguration()
          .get(Property.TSERV_LAST_LOCATION_MODE.getKey()));

      // no last location should be set yet
      TabletLocationState unflushed = getTabletLocationState(c, tableId);
      assertEquals(newTablet.current, unflushed.current);
      assertNull(unflushed.last);
      assertNull(newTablet.future);

      // This should give it a last location if the mode is being used correctly
      c.tableOperations().flush(tableName, null, null, true);

      TabletLocationState flushed = getTabletLocationState(c, tableId);
      assertEquals(newTablet.current, flushed.current);
      assertEquals(flushed.current, flushed.last);
      assertNull(newTablet.future);

      // take the tablet offline
      c.tableOperations().offline(tableName, true);
      TabletLocationState offline = getTabletLocationState(c, tableId);
      assertNull(offline.future);
      assertNull(offline.current);
      assertEquals(flushed.current, offline.last);

      // put it back online, should have the same last location
      c.tableOperations().online(tableName, true);
      TabletLocationState online = getTabletLocationState(c, tableId);
      assertNull(online.future);
      assertNotNull(online.current);
      assertEquals(offline.last, online.last);
    }
  }

  private TabletLocationState getTabletLocationState(AccumuloClient c, String tableId) {
    try (MetaDataTableScanner s = new MetaDataTableScanner((ClientContext) c,
        new Range(TabletsSection.encodeRow(TableId.of(tableId), null)), MetadataTable.NAME)) {
      return s.next();
    }
  }
}
