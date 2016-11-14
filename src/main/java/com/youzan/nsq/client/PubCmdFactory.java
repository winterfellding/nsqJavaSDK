package com.youzan.nsq.client;

import com.youzan.nsq.client.configs.ConfigAccessAgent;
import com.youzan.nsq.client.core.command.Pub;
import com.youzan.nsq.client.core.command.PubTrace;
import com.youzan.nsq.client.entity.Message;
import com.youzan.nsq.client.entity.NSQConfig;
import com.youzan.nsq.client.entity.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Publish command factory to create Pub commands based on pass in params in {@link com.youzan.nsq.client.entity.Message}
 * Created by lin on 16/10/28.
 */
public class PubCmdFactory implements IConfigAccessSubscriber{
    private final static Logger logger = LoggerFactory.getLogger(PubCmdFactory.class);

    private final static String NSQ_TOPIC_TRACE_KEY = "nsq.topic.trace.key";
    private final static String DEFAULT_NSQ_TOPIC_TRACE_PRODUCER_KEY = "trace";

    //topic trace map, for example: JavaTesting-Producer-Base -> true, means trace is on for topic "JavaTesting-Producer-Base"
    private volatile Map<String, String> topicTrace = new TreeMap<>();
    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final ConfigAccessAgent.IConfigAccessCallback topicTraceUpdateHandler = new ConfigAccessAgent.IConfigAccessCallback() {
        @Override
        public void fallback(SortedMap itemsInCache, Object... objs) {
            if(null == itemsInCache || itemsInCache.size() == 0)
                return;
            try {
                lock.writeLock().lock();
                topicTrace = itemsInCache;
            }finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public void process(SortedMap newItems) {
            if(null == newItems || newItems.size() == 0)
                return;
            try {
                lock.writeLock().lock();
                topicTrace = newItems;
            }finally {
                lock.writeLock().unlock();
            }
        }
    };

    private static Object LOCK = new Object();
    private static PubCmdFactory _INSTANCE = null;


    private PubCmdFactory(){

    }

    public static PubCmdFactory getInstance(){
        if(null ==_INSTANCE){
            synchronized(LOCK){
                if(null == _INSTANCE){
                    _INSTANCE = new PubCmdFactory();
                    try {
                        _INSTANCE.subscribe(ConfigAccessAgent.getInstance());
                    }catch(Exception e){
                        logger.error("Fail to subscribe to ConfigAccessAgent.");
                        throw e;
                    }
                }
            }
        }
        return _INSTANCE;
    }

    /**
     * Create Pub command, given pass in Message object
     * @param msg msg object passin
     * @return Pub command instance
     */
    public Pub create(final Message msg, final NSQConfig config){
        if(isTracedMessage(msg)){
            return new PubTrace(msg);
        }else{
            return new Pub(msg);
        }
    }


    /**
     * check if message pass in:
     * 1. has config which indicate that topic it is about to be sent to has trace config on.
     * @param msg
     * @return
     */
    private boolean isTracedMessage(final Message msg){
        String flag = null;
        try{
            Topic topic = msg.getTopic();
            lock.readLock().lock();
            //check trace map
            flag = this.topicTrace.get(topic.getTopicText());
        }finally {
            lock.readLock().unlock();
        }

        if(null == flag || !Boolean.valueOf(flag))
            return false;
        else {
            //mark message as traced
            msg.traced();
            return true;
        }
    }

    @Override
    public String getDomain() {
        String domain = ConfigAccessAgent.getProperty(NSQ_APP_VAL);
        return null == domain ? DEFAULT_NSQ_APP_VAL : domain;
    }

    @Override
    public String[] getKeys() {
        String[] keys = new String[1];
        String key = ConfigAccessAgent.getProperty(NSQ_TOPIC_TRACE_KEY);
        keys[0] = null == key ? DEFAULT_NSQ_TOPIC_TRACE_PRODUCER_KEY : key;
        return keys;
    }

    @Override
    public ConfigAccessAgent.IConfigAccessCallback getCallback() {
        return this.topicTraceUpdateHandler;
    }

    @Override
    public void subscribe(ConfigAccessAgent subscribeTo) {
        logger.info("PubCmdFactory Instance subscribe to {}.", subscribeTo);
        SortedMap<String, String> firstLookupMap = subscribeTo.handleSubscribe(getDomain(), getKeys(), getCallback());
        if(null == firstLookupMap || firstLookupMap.size() == 0)
            return;
        try {
            lock.writeLock().lock();
            topicTrace = firstLookupMap;
        }finally {
            lock.writeLock().unlock();
        }
    }
}