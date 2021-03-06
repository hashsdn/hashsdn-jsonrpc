/*
 * Copyright (c) 2017 Brocade Communications Systems, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.jsonrpc.impl;

import java.io.IOException;

import org.opendaylight.jsonrpc.bus.jsonrpc.JsonRpcReplyMessage;
import org.opendaylight.jsonrpc.bus.jsonrpc.JsonRpcRequestMessage;
import org.opendaylight.jsonrpc.bus.messagelib.RequestMessageHandler;
import org.opendaylight.jsonrpc.model.RemoteOmShard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.google.gson.JsonPrimitive;

/**
 * {@link RequestMessageHandler} which acts as mock implementation of
 * {@link RemoteOmShard}.
 * 
 * @author <a href="mailto:rkosegi@brocade.com">Richard Kosegi</a>
 *
 */
public class OmRootMessageHandler implements RequestMessageHandler, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OmRootMessageHandler.class);
    private int governancePort;

    public OmRootMessageHandler(int governancePort) {
        this.governancePort = governancePort;
    }

    @Override
    public void handleRequest(JsonRpcRequestMessage request, JsonRpcReplyMessage reply) {
        LOG.info("Req : {}", request);
        try {
            switch (request.getMethod()) {
            case "source":
                reply.setResult(new JsonPrimitive(getYangSource(request.getParams().getAsString())));
                return;
            case "governance":
                reply.setResult(new JsonPrimitive(String.format("zmq://localhost:%d", governancePort)));
                return;
            case "close":
                reply.setResult(new JsonPrimitive("ok"));
                return;
            default:
                reply.setResultAsObject("ERROR : unknown method : " + request.getMethod());
                return;
            }
        } catch (Exception e) {
            LOG.error("I/O error", e);
            reply.setResultAsObject("ERROR");
        }
    }

    private String getYangSource(String moduleName) throws IOException {
        String str = Resources.toString(Resources.getResource(getClass(), "/" + moduleName + ".yang"),
                Charsets.US_ASCII);
        return str;
    }

    @Override
    public void close() throws Exception {
        // no-op
    }
}
