/*
 * Copyright (c) 2017 Brocade Communications Systems, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.jsonrpc.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URISyntaxException;
import java.util.Map.Entry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.jsonrpc.bus.messagelib.TransportFactory;
import org.opendaylight.jsonrpc.hmap.DataType;
import org.opendaylight.jsonrpc.hmap.HierarchicalEnumHashMap;
import org.opendaylight.jsonrpc.hmap.HierarchicalEnumMap;
import org.opendaylight.jsonrpc.hmap.JsonPathCodec;
import org.opendaylight.jsonrpc.model.RemoteOmShard;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev130712.NetworkTopology;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Tests for {@link JsonRPCTx}.
 * 
 * @author <a href="mailto:rkosegi@brocade.com">Richard Kosegi</a>
 *
 */
public class JsonRPCTxTest extends AbstractJsonRpcTest {
    private static final Logger LOG = LoggerFactory.getLogger(JsonRPCTxTest.class);
    private static final String ENDPOINT = "zmq://localhost:1234";
    private static final String DEVICE_NAME = "dev-1";
    private JsonRPCTx trx;
    private JsonConverter conv;
    private RemoteOmShard om;
    private TransportFactory transportFactory;
    private HierarchicalEnumMap<JsonElement, DataType, String> pathMap;

    @Before
    public void setUp() throws URISyntaxException {
        pathMap = HierarchicalEnumHashMap.create(DataType.class, JsonPathCodec.create());
        pathMap.put(new JsonObject(), DataType.CONFIGURATION_DATA, ENDPOINT);
        pathMap.put(new JsonObject(), DataType.OPERATIONAL_DATA, ENDPOINT);
        transportFactory = mock(TransportFactory.class);
        NormalizedNodesHelper.init(schemaContext);
        om = mock(RemoteOmShard.class);
        doReturn(om).when(transportFactory).createProxy(any(), anyString());
        conv = new JsonConverter(schemaContext);
        trx = new JsonRPCTx(transportFactory, DEVICE_NAME, pathMap, conv, schemaContext);
    }

    @After
    public void tearDown() {
        trx.close();
        reset(om);
    }

    @Test
    public void test_read() throws Exception {
        final JsonElement elem = new JsonObject();
        doReturn(elem).when(om).read(eq(Util.store2str(Util.store2int(LogicalDatastoreType.OPERATIONAL))), eq(DEVICE_NAME),
                any(JsonElement.class));
        final CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> fopt = trx
                .read(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.of(NetworkTopology.QNAME));

        final NormalizedNode<?, ?> nn = fopt.checkedGet().get();
        LOG.info("Read output : {}", nn);
        assertEquals(NetworkTopology.QNAME.getNamespace().toString(), nn.getNodeType().getNamespace().toString());
        assertNotNull(nn.getValue());
    }

    @Test
    public void test_read_null() throws Exception {
        /* Special case - null read (allowed by RPC spec) should result in an empty container
         * and no barfs on the ODL side */
        final JsonElement elem = null;
        doReturn(elem).when(om).read(eq(Util.store2str(Util.store2int(LogicalDatastoreType.OPERATIONAL))), eq(DEVICE_NAME),
                any(JsonElement.class));
        final CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> fopt = trx
                .read(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.of(NetworkTopology.QNAME));

        final NormalizedNode<?, ?> nn = fopt.checkedGet().get();
        LOG.info("Read output : {}", nn);
    }
    @Test
    public void test_exists() throws Exception {
        doReturn(true).when(om).exists(anyString(), anyString(), any(JsonElement.class));
        assertTrue(trx.exists(LogicalDatastoreType.CONFIGURATION, YangInstanceIdentifier.of(NetworkTopology.QNAME))
                .checkedGet());
        verify(om, times(1)).exists(eq("config"), anyString(),
                any(JsonElement.class));
    }

    @Test
    public void test_put() throws TransactionCommitFailedException {
        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> data = JsonConverterTest.createContainerNodeData();
        trx.put(LogicalDatastoreType.CONFIGURATION, data.getKey(), data.getValue());
        doReturn(true).when(om).commit(anyString());
        CheckedFuture<Void, TransactionCommitFailedException> rf = trx.submit();
        rf.checkedGet();
        assertTrue(rf.isDone());
        verify(om, times(1)).put(anyString(), eq("config"), anyString(),
                any(JsonElement.class), any(JsonElement.class));
    }

    @Test
    public void delete() throws TransactionCommitFailedException {
        trx.delete(LogicalDatastoreType.CONFIGURATION, YangInstanceIdentifier.of(NetworkTopology.QNAME));
        CheckedFuture<Void, TransactionCommitFailedException> rf = trx.submit();
        assertTrue(rf.isDone());
        verify(om, times(1)).delete(anyString(), eq("config"), anyString(),
                any(JsonElement.class));
        assertNotNull(trx.getIdentifier());
    }

    @Test
    public void cancel() throws TransactionCommitFailedException {
        trx.delete(LogicalDatastoreType.CONFIGURATION, YangInstanceIdentifier.of(NetworkTopology.QNAME));
        assertTrue(trx.cancel());
        verify(om, times(1)).delete(anyString(), eq("config"), anyString(),
                any(JsonElement.class));
        assertNotNull(trx.getIdentifier());
    }

    @Test
    public void test_merge() throws TransactionCommitFailedException {
        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> data = JsonConverterTest.createContainerNodeData();
        doReturn(true).when(om).commit(anyString());
        trx.merge(LogicalDatastoreType.CONFIGURATION, data.getKey(), data.getValue());
        CheckedFuture<Void, TransactionCommitFailedException> rf = trx.submit();
        rf.checkedGet();
        assertTrue(rf.isDone());
        verify(om, times(1)).merge(anyString(), eq("config"), anyString(),
                any(JsonElement.class), any(JsonElement.class));
    }
}
