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

package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.bytes.IORuntimeException;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.EngineReplication.ReplicationEntry;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.SubscriptionConsumer;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetNotFoundException;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.fs.Clusters;
import net.openhft.chronicle.engine.fs.HostDetails;
import net.openhft.chronicle.engine.tree.HostIdentifier;
import net.openhft.chronicle.hash.replication.EngineReplicationLangBytesConsumer;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.ChronicleMapBuilder;
import net.openhft.chronicle.map.MapEventListener;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

import static net.openhft.chronicle.engine.api.pubsub.SubscriptionConsumer.notifyEachEvent;
import static net.openhft.chronicle.hash.replication.SingleChronicleHashReplication.builder;


public class ChronicleMapKeyValueStore<K, MV, V> implements AuthenticatedKeyValueStore<K, MV, V>,
        Closeable,Supplier<EngineReplication> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChronicleMapKeyValueStore.class);
    private final ChronicleMap<K, V> chronicleMap;
    private final ObjectKVSSubscription<K, MV, V> subscriptions;
    private final EngineReplication engineReplicator;
    private final Asset asset;
    private final String assetFullName;

    public ChronicleMapKeyValueStore(@NotNull RequestContext context, Asset asset) {
        String basePath = context.basePath();
        double averageValueSize = context.getAverageValueSize();
        long maxEntries = context.getEntries();
        this.asset = asset;
        this.assetFullName = asset.fullName();
        this.subscriptions = asset.acquireView(ObjectKVSSubscription.class, context);
        this.subscriptions.setKvStore(this);

        PublishingOperations publishingOperations = new PublishingOperations();

        ChronicleMapBuilder<K, V> builder = ChronicleMapBuilder.of(context.keyType(), context.valueType());
        HostIdentifier hostIdentifier = null;
        EngineReplication engineReplicator1 = null;
        try {
            engineReplicator1 = asset.acquireView(EngineReplication.class, RequestContext.requestContext());

            final EngineReplicationLangBytesConsumer langBytesConsumer = asset.acquireView
                    (EngineReplicationLangBytesConsumer.class, null);

            hostIdentifier = asset.acquireView(HostIdentifier.class, RequestContext.requestContext());

            builder.replication(builder().engineReplication(langBytesConsumer)
                    .createWithId(hostIdentifier.hostId()));

        } catch (AssetNotFoundException anfe) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("replication not enabled " + anfe.getMessage());
        }

        this.engineReplicator = engineReplicator1;
        builder.eventListener(publishingOperations);

        if (context.putReturnsNull() != Boolean.FALSE)
            builder.putReturnsNull(true);
        if (context.removeReturnsNull() != Boolean.FALSE)
            builder.removeReturnsNull(true);
        if (averageValueSize > 0)
            builder.averageValueSize(averageValueSize);
        if (maxEntries > 0)
            builder.entries(maxEntries);

        if (basePath == null)
            builder.create();
        else {
            String pathname = basePath + "/" + context.name();
            new File(basePath).mkdirs();
            try {
                builder.createPersistedTo(new File(pathname));
            } catch (IOException e) {
                IORuntimeException iore = new IORuntimeException("Could not access " + pathname);
                iore.initCause(e);
                throw iore;
            }
        }

        chronicleMap = builder.create();

        if (hostIdentifier != null) {
            Clusters clusters = asset.findView(Clusters.class);
            Map<String, HostDetails> hdMap = clusters.get("cluster");
            int hostId = hostIdentifier.hostId();
            for (HostDetails hostDetails : hdMap.values()) {
                if (hostDetails.hostId == hostId)
                    continue;

                TcpChannelHub tcpChannelHub = hostDetails.acquireTcpChannelHub();
                ReplicationHub replicationHub = new ReplicationHub(context, tcpChannelHub);

                try {
                    replicationHub.bootstrap(engineReplicator1, hostId);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        }

    }

    @NotNull
    @Override
    public KVSSubscription<K, MV, V> subscription(boolean createIfAbsent) {
        return subscriptions;
    }

    @Override
    public V getAndPut(K key, V value) {
        return chronicleMap.put(key, value);
    }

    @Override
    public V getAndRemove(K key) {
        return chronicleMap.remove(key);
    }

    @Override
    public V getUsing(K key, @Nullable MV value) {
        if (value != null) throw new UnsupportedOperationException("Mutable values not supported");
        return chronicleMap.getUsing(key, (V) value);
    }

    @Override
    public long longSize() {
        return chronicleMap.size();
    }

    @Override
    public void keysFor(int segment, @NotNull SubscriptionConsumer<K> kConsumer) throws InvalidSubscriberException {
        //Ignore the segments and return keysFor the whole map
        notifyEachEvent(chronicleMap.keySet(), kConsumer);
    }

    @Override
    public void entriesFor(int segment, @NotNull SubscriptionConsumer<MapEvent<K, V>> kvConsumer) throws InvalidSubscriberException {
        //Ignore the segments and return entriesFor the whole map
        chronicleMap.entrySet().stream().map(e -> InsertedEvent.of(assetFullName, e.getKey(), e.getValue())).forEach(e -> {
            try {
                kvConsumer.accept(e);
            } catch (InvalidSubscriberException t) {
                throw Jvm.rethrow(t);
            }
        });
    }

    @NotNull
    @Override
    public Iterator<Map.Entry<K, V>> entrySetIterator() {
        return chronicleMap.entrySet().iterator();
    }

    @Override
    public Iterator<K> keySetIterator() {
        return chronicleMap.keySet().iterator();
    }

    @Override
    public void clear() {
        chronicleMap.clear();
    }

    @Override
    public boolean containsValue(final MV value) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public Asset asset() {
        return asset;
    }

    @Nullable
    @Override
    public KeyValueStore<K, MV, V> underlying() {
        return null;
    }

    @Override
    public void close() {
        chronicleMap.close();
    }

    @Override
    public void accept(final ReplicationEntry replicationEntry) {
        engineReplicator.applyReplication(replicationEntry);
    }

    @Override
    public EngineReplication get() {
        return engineReplicator;
    }

    class PublishingOperations extends MapEventListener<K, V> {
        @Override
        public void onRemove(@NotNull K key, V value, boolean replicationEven) {
            subscriptions.notifyEvent(RemovedEvent.of(assetFullName, key, value));
        }

        @Override
        public void onPut(@NotNull K key, V newValue, @Nullable V replacedValue, boolean replicationEvent) {
            if (replacedValue != null) {
                subscriptions.notifyEvent(UpdatedEvent.of(assetFullName, key, replacedValue, newValue));
            } else {
                subscriptions.notifyEvent(InsertedEvent.of(assetFullName, key, newValue));
        }
    }
    }

}
