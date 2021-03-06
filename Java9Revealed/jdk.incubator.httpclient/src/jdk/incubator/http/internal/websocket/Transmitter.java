/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package jdk.incubator.http.internal.websocket;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/*
 * Sends messages one at a time, in an asynchronous and non-blocking fashion.
 *
 * No matter whether the message has been fully sent or an error has occurred,
 * the transmitter reports the outcome to the supplied handler and becomes ready
 * to accept a new message. Until then, it is considered "busy" and an
 * IllegalStateException will be thrown on each attempt to invoke send.
 */
final class Transmitter {

    private final AtomicBoolean busy = new AtomicBoolean();
    private OutgoingMessage message;
    private Consumer<Exception> completionHandler;
    private final RawChannel channel;
    private final RawChannel.RawEvent event;

    Transmitter(RawChannel channel) {
        this.channel = requireNonNull(channel);
        this.event = createHandler();
    }

    /*
     * The supplied handler may be invoked in the calling thread, so watch out
     * for stack overflow.
     */
    void send(OutgoingMessage message, Consumer<Exception> completionHandler) {
        requireNonNull(message);
        requireNonNull(completionHandler);
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException();
        }
        send0(message, completionHandler);
    }

    private RawChannel.RawEvent createHandler() {
        return new RawChannel.RawEvent() {

            @Override
            public int interestOps() {
                return SelectionKey.OP_WRITE;
            }

            @Override
            public void handle() {
                // registerEvent(e) happens-before subsequent e.handle(), so
                // we're fine reading the stored message and the completionHandler
                send0(message, completionHandler);
            }
        };
    }

    private void send0(OutgoingMessage message, Consumer<Exception> handler) {
        boolean b = busy.get();
        assert b; // Please don't inline this, as busy.get() has memory
                  // visibility effects and we don't want the correctness
                  // of the algorithm to depend on assertions flag
        try {
            boolean sent = message.sendTo(channel);
            if (sent) {
                busy.set(false);
                handler.accept(null);
            } else {
                // The message has not been fully sent, the transmitter needs to
                // remember the message until it can continue with sending it
                this.message = message;
                this.completionHandler = handler;
                try {
                    channel.registerEvent(event);
                } catch (IOException e) {
                    this.message = null;
                    this.completionHandler = null;
                    busy.set(false);
                    handler.accept(e);
                }
            }
        } catch (IOException e) {
            busy.set(false);
            handler.accept(e);
        }
    }
}
