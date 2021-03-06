/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.openhft.chronicle.engine.server;

import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.server.internal.EngineWireHandler;
import net.openhft.chronicle.network.AcceptorEventHandler;
import net.openhft.chronicle.network.VanillaSessionDetails;
import net.openhft.chronicle.threads.EventGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * Created by Rob Austin
 */
public class ServerEndpoint implements Closeable {

    private EventGroup eg;
    private AcceptorEventHandler eah;

    public ServerEndpoint(AssetTree assetTree) throws
            IOException {
        this(0, true, assetTree);
    }

    public ServerEndpoint(int port, boolean daemon, AssetTree assetTree) throws IOException {
        eg = new EventGroup(daemon);
        start(port, assetTree);
    }

    @Nullable
    public AcceptorEventHandler start(int port, @NotNull final AssetTree asset) throws IOException {
        eg.start();

        AcceptorEventHandler eah = new AcceptorEventHandler(port, () -> new EngineWireHandler(WireType.wire, asset), VanillaSessionDetails::new);

        eg.addHandler(eah);
        this.eah = eah;
        return eah;
    }

    public int getPort() throws IOException {
        return eah.getLocalPort();
    }

    public void stop() {
        eg.stop();
    }

    @Override
    public void close() {
        stop();
        closeQuietly(eg);
        eg = null;
        closeQuietly(eah);
        eah = null;

    }
}
