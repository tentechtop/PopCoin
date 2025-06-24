package io.ep2p.kademlia.node.builder;

import io.ep2p.kademlia.NodeSettings;
import io.ep2p.kademlia.connection.ConnectionInfo;
import io.ep2p.kademlia.connection.MessageSender;
import io.ep2p.kademlia.node.*;
import io.ep2p.kademlia.repository.KademliaRepository;
import io.ep2p.kademlia.services.DHTLookupServiceFactory;
import io.ep2p.kademlia.services.DHTStoreServiceFactory;
import io.ep2p.kademlia.table.Bucket;
import io.ep2p.kademlia.table.RoutingTable;

import java.io.Serializable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;


/**
 * @param <I> Type of ID (number)
 * @param <C> Type of connection info
 * @param <K> Type of keys in DHT (serializable)
 * @param <V> Type of values in DHT (serializable)
 */
public class DHTKademliaNodeBuilder<I extends Number, C extends ConnectionInfo, K extends Serializable, V extends Serializable> {
    private I id;
    private C connectionInfo;
    private RoutingTable<I, C, Bucket<I, C>> routingTable;
    private MessageSender<I, C> messageSender;
    private KeyHashGenerator<I, K> keyHashGenerator;
    private KademliaRepository<K, V> kademliaRepository;
    private NodeSettings nodeSettings;
    private ScheduledExecutorService scheduledExecutorService;
    private ExecutorService dhtExecutorService;
    private DHTStoreServiceFactory<I, C, K, V> dhtStoreServiceFactory;
    private DHTLookupServiceFactory<I, C, K, V> dhtLookupServiceFactory;

    /**
     * @param id ID of the node
     * @param connectionInfo Connection info of the node
     * @param routingTable Routing table of the node
     * @param messageSender MessageSender implementation
     * @param keyHashGenerator DHT KeyHashGenerator implementation
     * @param kademliaRepository DHT repository
     */
    public DHTKademliaNodeBuilder(I id, C connectionInfo, RoutingTable<I, C, Bucket<I, C>> routingTable, MessageSender<I, C> messageSender, KeyHashGenerator<I, K> keyHashGenerator, KademliaRepository<K, V> kademliaRepository){
        setId(id);
        setConnectionInfo(connectionInfo);
        setRoutingTable(routingTable);
        setMessageSender(messageSender);
        setKeyHashGenerator(keyHashGenerator);
        setKademliaRepository(kademliaRepository);
    }

    /**
     * @return built DHTKademliaNodeAPI
     */
    public DHTKademliaNodeAPI<I, C, K, V> build(){
        return new DHTKademliaNode<>(this.buildKademliaNode(), getKeyHashGenerator(), getKademliaRepository(), getDhtStoreServiceFactory(), getDhtLookupServiceFactory());
    }

    protected KademliaNodeAPI<I, C> buildKademliaNode(){
        return new KademliaNode<>(getId(), getConnectionInfo(), getRoutingTable(), getMessageSender(), getNodeSettings(), getScheduledExecutorService());
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setId(I id) {
        this.id = id;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setConnectionInfo(C connectionInfo) {
        this.connectionInfo = connectionInfo;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setRoutingTable(RoutingTable<I, C, Bucket<I, C>> routingTable) {
        this.routingTable = routingTable;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setMessageSender(MessageSender<I, C> messageSender) {
        this.messageSender = messageSender;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setNodeSettings(NodeSettings nodeSettings) {
        this.nodeSettings = nodeSettings;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setDhtExecutorService(ExecutorService dhtExecutorService) {
        this.dhtExecutorService = dhtExecutorService;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setKeyHashGenerator(KeyHashGenerator<I, K> keyHashGenerator) {
        this.keyHashGenerator = keyHashGenerator;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setKademliaRepository(KademliaRepository<K, V> kademliaRepository) {
        this.kademliaRepository = kademliaRepository;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setDhtStoreServiceFactory(DHTStoreServiceFactory<I, C, K, V> dhtStoreServiceFactory) {
        this.dhtStoreServiceFactory = dhtStoreServiceFactory;
        return this;
    }

    public DHTKademliaNodeBuilder<I, C, K, V> setDhtLookupServiceFactory(DHTLookupServiceFactory<I, C, K, V> dhtLookupServiceFactory) {
        this.dhtLookupServiceFactory = dhtLookupServiceFactory;
        return this;
    }

    protected I getId() {
        return id;
    }

    protected C getConnectionInfo() {
        return connectionInfo;
    }

    protected RoutingTable<I, C, Bucket<I, C>> getRoutingTable() {
        return routingTable;
    }

    protected MessageSender<I, C> getMessageSender() {
        return messageSender;
    }

    protected NodeSettings getNodeSettings() {
        return nodeSettings == null ? NodeSettings.Default.build() : nodeSettings;
    }

    protected ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService == null ? Executors.newSingleThreadScheduledExecutor() : scheduledExecutorService;
    }

    protected synchronized ExecutorService getDhtExecutorService() {
        if (dhtExecutorService == null){
            dhtExecutorService = Executors.newFixedThreadPool(getNodeSettings().getDhtExecutorPoolSize());
        }
        return dhtExecutorService;
    }

    protected KeyHashGenerator<I, K> getKeyHashGenerator() {
        return keyHashGenerator;
    }

    protected KademliaRepository<K, V> getKademliaRepository() {
        return kademliaRepository;
    }

    protected DHTStoreServiceFactory<I, C, K, V> getDhtStoreServiceFactory() {
        return dhtStoreServiceFactory != null ? dhtStoreServiceFactory : new DHTStoreServiceFactory.DefaultDHTStoreServiceFactory<>(
                getDhtExecutorService()
        );
    }

    protected DHTLookupServiceFactory<I, C, K, V> getDhtLookupServiceFactory() {
        return dhtLookupServiceFactory != null ? dhtLookupServiceFactory : new DHTLookupServiceFactory.DefaultDHTLookupServiceFactory<>(
                getDhtExecutorService()
        );
    }
}
