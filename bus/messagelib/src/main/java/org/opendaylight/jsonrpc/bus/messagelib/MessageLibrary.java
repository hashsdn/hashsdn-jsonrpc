/*
 * Copyright (c) 2017 Brocade Communications Systems, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.jsonrpc.bus.messagelib;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.opendaylight.jsonrpc.bus.BusSession;
import org.opendaylight.jsonrpc.bus.BusSessionFactory;
import org.opendaylight.jsonrpc.bus.spi.BusSessionFactoryProvider;

import com.google.common.collect.Lists;

/**
 * The is the main class to create sessions over a bus. This class will help
 * create a {@link BusSessionFactory}, then use it to create sessions of various
 * types.
 *
 * @author Shaleen Saxena
 *
 */
public class MessageLibrary implements AutoCloseable {
    private BusSessionFactory<BusSession> factory;
    private final List<Session> sessions = new ArrayList<>();

    /**
     * Default constructor which uses {@link TcclBusSessionFactoryProvider} to
     * discover installed transports
     * 
     * @param busType bus type to get
     */
    public MessageLibrary(String busType) {
        // scan classpath using TCCL classloader and find service
        // implementations
        this(TcclBusSessionFactoryProvider.getInstance(), busType);
    }

    /**
     * Constructor which allows usage of custom
     * {@link TcclBusSessionFactoryProvider} to discover installed transports
     *
     * @param bsfp Bus Session Factory Provider
     * @param busType bus type to get
     */
    public MessageLibrary(BusSessionFactoryProvider bsfp, String busType) {
        final Iterator<BusSessionFactory<BusSession>> it = bsfp.getBusSessionFactories();
        while (it.hasNext()) {
            final BusSessionFactory<BusSession> f = it.next();
            if (busType.equalsIgnoreCase(f.name())) {
                factory = f;
            }
        }
        if (factory == null) {
            throw new IllegalArgumentException(String.format("Bus Type not supported : %s", busType));
        }
    }

    public void add(Session session) {
        sessions.add(session);
    }

    public void remove(Session session) {
        if (sessions.contains(session)) {
            sessions.remove(session);
        }
    }

    @Override
    public void close() {
        Lists.newArrayList(sessions).spliterator().forEachRemaining(Session::close);
        factory.close();
    }

    public Session subscriber(String uri) {
        return new Session(this, factory.subscriber(uri, ""));
    }

    public Session publisher(String uri) {
        return new Session(this, factory.publisher(uri, ""));
    }

    public Session requester(String uri) {
        return new Session(this, factory.requester(uri));
    }

    public Session responder(String uri) {
        return new Session(this, factory.responder(uri));
    }

    public <T extends AutoCloseable> ThreadedSession threadedSubscriber(String uri, T handler) {
        return new ThreadedSessionImpl<T>(this, factory.subscriber(uri, ""), handler);
    }

    public <T extends AutoCloseable> ThreadedSession threadedResponder(String uri, T handler) {
        return new ThreadedSessionImpl<T>(this, factory.responder(uri), handler);
    }
}
