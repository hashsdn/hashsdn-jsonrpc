/*
 * Copyright (c) 2017 Brocade Communications Systems, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.jsonrpc.impl;

import static org.opendaylight.jsonrpc.impl.Util.*;

import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import org.opendaylight.controller.md.sal.common.api.TransactionStatus;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataReadOnlyTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMDataReadWriteTransaction;
import org.opendaylight.jsonrpc.bus.messagelib.EndpointRole;
import org.opendaylight.jsonrpc.bus.messagelib.TransportFactory;
import org.opendaylight.jsonrpc.hmap.DataType;
import org.opendaylight.jsonrpc.hmap.HierarchicalEnumMap;
import org.opendaylight.jsonrpc.model.JSONRPCArg;
import org.opendaylight.jsonrpc.model.RemoteOmShard;
import org.opendaylight.mdsal.common.api.MappingCheckedFuture;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.codec.gson.JsonParserStream;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.impl.schema.NormalizedNodeResult;
import org.opendaylight.yangtools.yang.model.api.DataNodeContainer;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

@SuppressWarnings("deprecation")
public class JsonRPCTx implements DOMDataReadWriteTransaction, DOMDataReadOnlyTransaction {
    private static final Logger LOG = LoggerFactory.getLogger(JsonRPCTx.class);
    private final SchemaContext schemaContext;
    private final String deviceName;
    private final JsonConverter jsonConverter;
    private final HierarchicalEnumMap<JsonElement, DataType, String> pathMap;
    private final TransportFactory transportFactory;

    /* Transaction ID */
    private Map<String, RemoteOmShard> endPointMap;
    private Map<String, String> txIdMap;

    /**
     * Instantiates a new ZMQ Bus Transaction.
     *
     * @param transportFactory used to create underlying transport connections
     * @param deviceName the bus om interface to use
     * @param pathMap shared instance of {@link HierarchicalEnumMap}
     * @param jsonConverter the conversion janitor instance
     * @param schemaContext the schema context
     */
    public JsonRPCTx(@Nonnull TransportFactory transportFactory, @Nonnull String deviceName,
            @Nonnull HierarchicalEnumMap<JsonElement, DataType, String> pathMap, @Nonnull JsonConverter jsonConverter,
            @Nonnull SchemaContext schemaContext) {
        this.transportFactory = Preconditions.checkNotNull(transportFactory);
        Preconditions.checkArgument(!Strings.isNullOrEmpty(deviceName), "Peer name is missing");
        this.deviceName = deviceName;
        this.pathMap = Preconditions.checkNotNull(pathMap);
        this.schemaContext = Preconditions.checkNotNull(schemaContext);
        this.jsonConverter = Preconditions.checkNotNull(jsonConverter);
        this.endPointMap = new HashMap<>();
        this.txIdMap = new HashMap<>();
        /*
         * Instantiate our "for real" bus interface. This is a simplification -
         * it assumes that the "ownership" of a device does not change across
         * the device tree. That may not be the case as a device is entitled to
         * be sharded, same as we are. We, however, will cross this bridge when
         * we cross it and we may end up crossing it on the bus side via proxy
         * functionality in the device tree implementation.
         */
    }

    private String lookupEndPoint(final LogicalDatastoreType store, JsonElement path) {
        return pathMap.lookup(path, DataType.forDatastore(store)).orElse(null);
    }

    private RemoteOmShard getOmShard(final LogicalDatastoreType store, JsonElement path) {
        final String endpoint = lookupEndPoint(store, path);
        return endPointMap.computeIfAbsent(endpoint, ep -> {
            try {
                final String fixedEndpoint = Util.ensureRole(endpoint, EndpointRole.REQ);
                return transportFactory.createProxy(RemoteOmShard.class, fixedEndpoint);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Provided URI is invalid", e);
            }
        });
    }

    private String getTxId(String endpoint) {
        return txIdMap.computeIfAbsent(endpoint, k -> endPointMap.get(k).txid());
    }

    @Override
    public CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> read(final LogicalDatastoreType store,
            final YangInstanceIdentifier path) {
        final JSONRPCArg arg = jsonConverter.convert(path, null);
        if (path.getPathArguments().isEmpty()) {
            return readFailure();
        }
        final RemoteOmShard omshard = getOmShard(store, arg.path);
        /* Read from the bus and adjust for BUS to ODL differences */
        JsonObject rootJson = null;
        try {
            rootJson = jsonConverter.busToODL(path, omshard.read(store2str(store2int(store)), deviceName, arg.path));
        } catch (Exception e) {
            return readFailure(e);
        }

        if (rootJson == null) {
            return readFailure();
        }
        final NormalizedNodeResult result = new NormalizedNodeResult();
        JsonParserStream jsonParser = null;
        DataNodeContainer tracker = schemaContext;
        try (final NormalizedNodeStreamWriter streamWriter = ImmutableNormalizedNodeStreamWriter.from(result)) {
            final Iterator<PathArgument> pathIterator = path.getPathArguments().iterator();
            while (pathIterator.hasNext()) {
                final PathArgument step = pathIterator.next();
                if (pathIterator.hasNext()) {
                    final DataSchemaNode nextNode = tracker.getDataChildByName(step.getNodeType());
                    if (nextNode == null) {
                        LOG.error("cannot locate corresponding schema node {}", step.getNodeType().getLocalName());
                        return readFailure();
                    }
                    if (!DataNodeContainer.class.isInstance(nextNode)) {
                        LOG.error("corresponding schema node {} is neither list nor container",
                                step.getNodeType().getLocalName());
                        return readFailure();
                    }
                    /*
                     * List looks like a two path entry sequentially, so we need
                     * to skip one
                     */
                    if (!ListSchemaNode.class.isInstance(nextNode)) {
                        tracker = (DataNodeContainer) nextNode;
                    }
                }
            }

            jsonParser = JsonParserStream.create(streamWriter, schemaContext, (SchemaNode) tracker);
            /*
             * Kludge - we convert to string so that the StringReader can
             * consume it, we need to replace this with a native translator into
             * NormalizedNode
             */
            try {
                jsonParser.parse(new JsonReader(new StringReader(rootJson.toString())));
            } catch (IllegalArgumentException e) {
                LOG.error("Failed to parse read data {}", rootJson.toString());
                return readFailure(e);
            }
            final ListenableFuture<Optional<NormalizedNode<?, ?>>> future = Futures
                    .immediateFuture(Optional.<NormalizedNode<?, ?>>of(result.getResult()));
            switch (store) {
            case CONFIGURATION:
            case OPERATIONAL:
                return MappingCheckedFuture.create(future, ReadFailedException.MAPPER);
            default:
                throw new IllegalArgumentException(String.format(
                        "%s, Cannot read data %s for %s datastore, unknown datastore type", deviceName, path, store));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to close NormalizedNodeStreamWriter", e);
        } finally {
            Util.closeNullableWithExceptionCallback(jsonParser, e -> LOG.warn("Failed to close JsonParser", e));
        }
    }

    private CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> readFailure(Exception e) {
        return MappingCheckedFuture.create(Futures.immediateFailedFuture(e), ReadFailedException.MAPPER);
    }

    private CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> readFailure() {
        return MappingCheckedFuture.create(Futures.immediateFuture(Optional.<NormalizedNode<?, ?>>absent()),
                ReadFailedException.MAPPER);
    }

    @Override
    public CheckedFuture<Boolean, ReadFailedException> exists(LogicalDatastoreType store, YangInstanceIdentifier path) {
        final JSONRPCArg arg = jsonConverter.convert(path, null);
        final RemoteOmShard omshard = getOmShard(store, arg.path);
        try {
            return Futures.immediateCheckedFuture(omshard.exists(store2str(store2int(store)), deviceName, arg.path));
        } catch (Exception e) {
            return MappingCheckedFuture.create(Futures.immediateFailedFuture(e), ReadFailedException.MAPPER);
        }
    }

    @Override
    public void close() {
        endPointMap.entrySet().forEach(e -> Util.closeNullableWithExceptionCallback(e.getValue(),
                t -> LOG.warn("Failed to close RemoteOmShard proxy", t)));
    }

    @Override
    public void put(final LogicalDatastoreType store, final YangInstanceIdentifier path,
            final NormalizedNode<?, ?> data) {
        final JSONRPCArg arg = jsonConverter.convertWithStripControl(path, data, true);
        if (arg.data != null) {
            /* ODL supplies a null arg to create an entry before setting it */
            RemoteOmShard omshard = getOmShard(store, arg.path);
            /* this is ugly - extra lookup, needs fixing on another pass */
            omshard.put(getTxId(lookupEndPoint(store, arg.path)), store2str(store2int(store)), deviceName, arg.path, arg.data);
        }
    }

    @Override
    public void merge(final LogicalDatastoreType store, final YangInstanceIdentifier path,
            final NormalizedNode<?, ?> data) {
        final JSONRPCArg arg = jsonConverter.convert(path, data);
        final RemoteOmShard omshard = getOmShard(store, arg.path);
        omshard.merge(getTxId(lookupEndPoint(store, arg.path)), store2str(store2int(store)), deviceName, arg.path, arg.data);

    }

    @Override
    public void delete(final LogicalDatastoreType store, final YangInstanceIdentifier path) {
        final JSONRPCArg arg = jsonConverter.convert(path, null);
        final RemoteOmShard omshard = getOmShard(store, arg.path);
        omshard.delete(getTxId(lookupEndPoint(store, arg.path)), store2str(store2int(store)), deviceName, arg.path);
    }

    @Override
    public Object getIdentifier() {
        return this;
    }

    @Override
    public boolean cancel() {
        boolean result = true;
        for (Map.Entry<String, RemoteOmShard> entry : this.endPointMap.entrySet()) {
            RemoteOmShard omshard = this.endPointMap.get(entry.getKey());
            if (getTxId(entry.getKey()) != null) {
                /*
                 * We never allocated a txid, so no need to send message to om.
                 */
                result &= omshard.cancel(getTxId(entry.getKey()));
            }
        }
        return result;
    }

    @Override
    public CheckedFuture<Void, TransactionCommitFailedException> submit() {
        final ListenableFuture<Void> commmitFutureAsVoid = Futures.transform(commit(),
                (Function<RpcResult<TransactionStatus>, Void>) input -> null);

        return Futures.makeChecked(commmitFutureAsVoid,
                input -> new TransactionCommitFailedException("Submit of transaction " + getIdentifier() + " failed",
                        input));
    }

    @Override
    public ListenableFuture<RpcResult<TransactionStatus>> commit() {
        final AtomicBoolean result = new AtomicBoolean(true);
        endPointMap.keySet().forEach(k -> {
            result.set(result.get() && endPointMap.get(k).commit(getTxId(k)));
        });
        if (result.get()) {
            return Futures.immediateFuture(RpcResultBuilder.success(TransactionStatus.COMMITED).build());
        } else {
            final RpcResultBuilder<TransactionStatus> failed = RpcResultBuilder.failed();
            failed.withError(ErrorType.APPLICATION, "error committing transaction");
            return Futures.immediateFuture(failed.build());
        }
    }
}
