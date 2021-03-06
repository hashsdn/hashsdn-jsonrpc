/*
 * Copyright (c) 2017 Brocade Communications Systems, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.jsonrpc.model;

import java.net.URISyntaxException;

import org.opendaylight.jsonrpc.bus.messagelib.NotificationMessageHandler;
import org.opendaylight.jsonrpc.bus.messagelib.ThreadedSession;
import org.opendaylight.jsonrpc.bus.messagelib.TransportFactory;
import org.opendaylight.yangtools.yang.model.api.NotificationDefinition;

import com.google.common.base.Preconditions;

public class NotificationState {
    private final NotificationDefinition notification;
    private final ThreadedSession client;

    public NotificationState(NotificationDefinition notification, String endpoint, NotificationMessageHandler handler,
            final TransportFactory transportFactory) throws URISyntaxException {
        this.notification = Preconditions.checkNotNull(notification);
        this.client = transportFactory.createSubscriber(endpoint, handler);
    }

    public NotificationDefinition notification() {
        return this.notification;
    }

    public ThreadedSession client() {
        return this.client;
    }
}
