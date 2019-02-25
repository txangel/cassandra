/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.thrift;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import org.junit.BeforeClass;
import org.junit.Test;

import junit.framework.Assert;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.locator.SimpleStrategy;
import org.apache.cassandra.service.EmbeddedCassandraService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.thrift.TException;

public class MultiGetMultiSliceTest
{
    private static final String KEYSPACE = MultiGetMultiSliceTest.class.getSimpleName();
    private static final String CF_STANDARD = "Standard1";

    private static final ByteBuffer PARTITION_1 = ByteBufferUtil.bytes("Partition1");
    private static final ByteBuffer PARTITION_2 = ByteBufferUtil.bytes("Partition2");
    private static final ByteBuffer COLUMN_A = ByteBufferUtil.bytes("a");
    private static final ByteBuffer COLUMN_B = ByteBufferUtil.bytes("b");
    private static final ByteBuffer COLUMN_C = ByteBufferUtil.bytes("c");
    private static final ByteBuffer COLUMN_D = ByteBufferUtil.bytes("d");
    private static final ByteBuffer COLUMN_X = ByteBufferUtil.bytes("x");
    private static final ByteBuffer COLUMN_Y = ByteBufferUtil.bytes("y");
    private static final ByteBuffer COLUMN_Z = ByteBufferUtil.bytes("z");

    private static final KeyPredicate PARTITION_1_COLUMN_A = keyPredicateForColumns(PARTITION_1, COLUMN_A);
    private static final KeyPredicate PARTITION_1_COLUMN_B = keyPredicateForColumns(PARTITION_1, COLUMN_B);
    private static final KeyPredicate PARTITION_1_COLUMNS_AB = keyPredicateForColumns(PARTITION_1, COLUMN_A, COLUMN_B);
    private static final KeyPredicate PARTITION_1_COLUMNS_BC = keyPredicateForColumns(PARTITION_1, COLUMN_B, COLUMN_C);
    private static final KeyPredicate PARTITION_2_COLUMNS_BC = keyPredicateForColumns(PARTITION_2, COLUMN_B, COLUMN_C);

    private static final KeyPredicate PARTITION_1_RANGE_THREE_FROM_A_TO_Z
            = keyPredicateForRange(PARTITION_1, COLUMN_A, COLUMN_Z, 3);
    private static final KeyPredicate PARTITION_1_RANGE_THREE_FROM_B_TO_Z
            = keyPredicateForRange(PARTITION_1, COLUMN_B, COLUMN_Z, 3);

    private static CassandraServer server;

    @BeforeClass
    public static void defineSchema() throws ConfigurationException, IOException, TException
    {
        SchemaLoader.prepareServer();
        new EmbeddedCassandraService().start();
        ThriftSessionManager.instance.setCurrentSocket(new InetSocketAddress(9160));
        SchemaLoader.createKeyspace(KEYSPACE,
                                    SimpleStrategy.class,
                                    KSMetaData.optsWithRF(1),
                                    SchemaLoader.standardCFMD(KEYSPACE, CF_STANDARD));
        server = new CassandraServer();
        server.set_keyspace(KEYSPACE);
    }

    @Test
    public void differentPredicatesOnDifferentPartitions() throws Exception
    {
        ColumnParent cp = new ColumnParent(CF_STANDARD);
        addTheAlphabetToRow(PARTITION_1, cp);
        addTheAlphabetToRow(PARTITION_2, cp);

        List<KeyPredicate> request = ImmutableList.of(PARTITION_1_COLUMN_A, PARTITION_2_COLUMNS_BC);

        Map<KeyPredicate, List<ColumnOrSuperColumn>> result = server.multiget_multislice(request, cp, ConsistencyLevel.ONE);
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_A), result.get(PARTITION_1_COLUMN_A));
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_B, COLUMN_C), result.get(PARTITION_2_COLUMNS_BC));
        Assert.assertEquals(result.size(), 2);
    }

    @Test
    public void disjointPredicatesOnSamePartition() throws Exception
    {
        ColumnParent cp = new ColumnParent(CF_STANDARD);
        addTheAlphabetToRow(PARTITION_1, cp);

        List<KeyPredicate> request = ImmutableList.of(PARTITION_1_COLUMN_A, PARTITION_1_COLUMN_B);

        Map<KeyPredicate, List<ColumnOrSuperColumn>> result = server.multiget_multislice(request, cp, ConsistencyLevel.ONE);
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_A), result.get(PARTITION_1_COLUMN_A));
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_B), result.get(PARTITION_1_COLUMN_B));
        Assert.assertEquals(result.size(), 2);
    }

    @Test
    public void disjointRangePredicatesOnSamePartition() throws Exception
    {
        ColumnParent cp = new ColumnParent(CF_STANDARD);
        addTheAlphabetToRow(PARTITION_1, cp);

        KeyPredicate partition1RangeAB = keyPredicateForRange(PARTITION_1, COLUMN_A, COLUMN_B, 100);
        KeyPredicate partition1RangeCD = keyPredicateForRange(PARTITION_1, COLUMN_C, COLUMN_D, 100);
        List<KeyPredicate> request = ImmutableList.of(partition1RangeAB, partition1RangeCD);

        Map<KeyPredicate, List<ColumnOrSuperColumn>> result = server.multiget_multislice(request, cp, ConsistencyLevel.ONE);
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_A, COLUMN_B), result.get(partition1RangeAB));
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_C, COLUMN_D), result.get(partition1RangeCD));
        Assert.assertEquals(result.size(), 2);
    }

    @Test
    public void overlappingPredicatesOnSamePartition() throws Exception
    {
        ColumnParent cp = new ColumnParent(CF_STANDARD);
        addTheAlphabetToRow(PARTITION_1, cp);

        List<KeyPredicate> request = ImmutableList.of(PARTITION_1_COLUMNS_AB, PARTITION_1_COLUMNS_BC);

        Map<KeyPredicate, List<ColumnOrSuperColumn>> result = server.multiget_multislice(request, cp, ConsistencyLevel.ONE);
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_A, COLUMN_B), result.get(PARTITION_1_COLUMNS_AB));
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_B, COLUMN_C), result.get(PARTITION_1_COLUMNS_BC));
        Assert.assertEquals(result.size(), 2);
    }

    @Test
    public void overlappingPredicatesOnSamePartitionWithRange() throws Exception
    {
        ColumnParent cp = new ColumnParent(CF_STANDARD);
        addTheAlphabetToRow(PARTITION_1, cp);

        List<KeyPredicate> request = ImmutableList.of(PARTITION_1_COLUMN_B, PARTITION_1_RANGE_THREE_FROM_A_TO_Z);

        Map<KeyPredicate, List<ColumnOrSuperColumn>> result = server.multiget_multislice(request, cp, ConsistencyLevel.ONE);
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_B), result.get(PARTITION_1_COLUMN_B));
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_A, COLUMN_B, COLUMN_C), result.get(PARTITION_1_RANGE_THREE_FROM_A_TO_Z));
        Assert.assertEquals(result.size(), 2);
    }

    @Test
    public void overlappingRangePredicatesOnSamePartition() throws Exception
    {
        ColumnParent cp = new ColumnParent(CF_STANDARD);
        addTheAlphabetToRow(PARTITION_1, cp);

        List<KeyPredicate> request = ImmutableList.of(PARTITION_1_RANGE_THREE_FROM_A_TO_Z,
                                                      PARTITION_1_RANGE_THREE_FROM_B_TO_Z);

        Map<KeyPredicate, List<ColumnOrSuperColumn>> result = server.multiget_multislice(request, cp, ConsistencyLevel.ONE);
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_A, COLUMN_B, COLUMN_C), result.get(PARTITION_1_RANGE_THREE_FROM_A_TO_Z));
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_B, COLUMN_C, COLUMN_D), result.get(PARTITION_1_RANGE_THREE_FROM_B_TO_Z));
        Assert.assertEquals(result.size(), 2);
    }

    @Test
    public void differentOrderingOnRangePredicatesRespected() throws Exception {
        ColumnParent cp = new ColumnParent(CF_STANDARD);
        addTheAlphabetToRow(PARTITION_1, cp);

        KeyPredicate partition1RangeThreeFromAToZReversed = new KeyPredicate()
                .setKey(PARTITION_1)
                .setPredicate(new SlicePredicate()
                              .setSlice_range(new SliceRange()
                                              .setStart(COLUMN_Z)
                                              .setFinish(COLUMN_A)
                                              .setCount(3)
                                              .setReversed(true)));
        List<KeyPredicate> request = ImmutableList.of(PARTITION_1_RANGE_THREE_FROM_A_TO_Z,
                                                      partition1RangeThreeFromAToZReversed);

        Map<KeyPredicate, List<ColumnOrSuperColumn>> result = server.multiget_multislice(request, cp, ConsistencyLevel.ONE);
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_A, COLUMN_B, COLUMN_C), result.get(PARTITION_1_RANGE_THREE_FROM_A_TO_Z));
        assertColumnNamesMatchPrecisely(ImmutableList.of(COLUMN_Z, COLUMN_Y, COLUMN_X), result.get(partition1RangeThreeFromAToZReversed));
        Assert.assertEquals(result.size(), 2);
    }

    private static KeyPredicate keyPredicateForColumns(ByteBuffer key, ByteBuffer... columnNames) {
        return new KeyPredicate()
                .setKey(key)
                .setPredicate(slicePredicateForColumns(columnNames));
    }

    private static SlicePredicate slicePredicateForColumns(ByteBuffer... columnNames) {
        return new SlicePredicate()
                .setColumn_names(ImmutableList.copyOf(columnNames));
    }

    private static KeyPredicate keyPredicateForRange(ByteBuffer key, ByteBuffer start, ByteBuffer finish, int count) {
        return new KeyPredicate()
               .setKey(key)
               .setPredicate(slicePredicateForRange(start, finish, count));
    }


    private static SlicePredicate slicePredicateForRange(ByteBuffer start, ByteBuffer finish, int count) {
        return new SlicePredicate()
                .setSlice_range(new SliceRange().setStart(start).setFinish(finish).setCount(count));
    }

    private static void addTheAlphabetToRow(ByteBuffer key, ColumnParent parent)
            throws InvalidRequestException, UnavailableException, TimedOutException
    {
        for (char ch = 'a'; ch <= 'z'; ch++)
        {
            Column column = new Column()
                    .setName(ByteBufferUtil.bytes(String.valueOf(ch)))
                    .setValue(new byte [0])
                    .setTimestamp(System.nanoTime());
            server.insert(key, parent, column, ConsistencyLevel.ONE);
        }
    }

    private static void assertColumnNamesMatchPrecisely(List<ByteBuffer> expected, List<ColumnOrSuperColumn> actual)
    {
        Assert.assertEquals(actual + " " + expected + " did not have same number of elements", actual.size(), expected.size());
        for (int i = 0 ; i < expected.size() ; i++)
        {
            Assert.assertEquals(actual.get(i) + " did not equal " + expected.get(i),
                                expected.get(i), actual.get(i).getColumn().bufferForName());
        }
    }
}
