package com.roncoo.eshop.storm.zk;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * zk分布式锁的代码封装
 * zookeeper java client api去封装连接zk，以及获取分布式锁，还有释放分布式锁的代码
 * 先简单介绍一下zk分布式锁的原理
 * 我们通过去创建zk的一个临时node，来模拟给摸一个商品id加锁
 * zk会给你保证说，只会创建一个临时node，其他请求过来如果再要创建临时node，就会报错，NodeExistsException
 * 那么所以说，我们的所谓上锁，其实就是去创建某个product id对应的一个临时node
 * 如果临时node创建成功了，那么说明我们成功加锁了，此时就可以去执行对redis立面数据的操作
 * 如果临时node创建失败了，说明有人已经在拿到锁了，在操作reids中的数据，那么就不断的等待，直到自己可以获取到锁为止
 * 基于zk client api，去封装上面的这个代码逻辑
 * 释放一个分布式锁，去删除掉那个临时node就可以了，就代表释放了一个锁，那么此时其他的机器就可以成功创建临时node，获取到锁
 * 即使是用zk去实现一个分布式锁，也有很多种做法，有复杂的，也有简单的
 * 应该说，我演示的这种分布式锁的做法，是非常简单的一种，但是很实用，大部分情况下，用这种简单的分布式锁都能搞定
 */

public class ZookeeperSession implements Watcher {
    private static CountDownLatch connectedSemaphore = new CountDownLatch(1);
    private ZooKeeper zooKeeper;

    public ZookeeperSession() {
        // 去连接zookeeper server，创建会话的时候，是异步去进行的
        // 所以要给一个监听器，说告诉我们什么时候才是真正完成了跟zk server的连接
        try {
            this.zooKeeper = new ZooKeeper(
                    "139.9.105.242:2181,49.234.235.150:2181,115.28.211.17:2181",
                    50000,
                    new ZooKeeperWatcher());
            // 给一个状态CONNECTING，连接中
            System.out.println(zooKeeper.getState());
            // CountDownLatch
            // java多线程并发同步的一个工具类
            // 会传递进去一些数字，比如说1,2 ，3 都可以
            // 然后await()，如果数字不是0，那么就卡住，等待
            // 其他的线程可以调用coutnDown()，减1
            // 如果数字减到0，那么之前所有在await的线程，都会逃出阻塞的状态
            // 继续向下运行
            try {
                connectedSemaphore.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("ZooKeeper session established......");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void process(WatchedEvent event) {

    }

    /**
     * 建立zk session的watcher
     */
    private class ZooKeeperWatcher implements Watcher {
        @Override
        public void process(WatchedEvent event) {
            System.out.println("Receive watched event: " + event.getState());
            if(Event.KeeperState.SyncConnected == event.getState()) {
                connectedSemaphore.countDown();
            }
        }
    }

    /**
     * 获取分布式锁
     */
    public void  acquireDistributedLock() {
        String path = "/taskid-list-lock";
        try {
            zooKeeper.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            System.out.println("success to acquire lock for taskid-list-lock");
        } catch (Exception e) {
            // 如果那个商品对应的锁的node，已经存在了，就是已经被别人加锁了，那么就这里就会报错
            // NodeExistsException
            int count = 0;
            while (true) {
                try {
                    Thread.sleep(20);
                    zooKeeper.create(path, "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    System.out.println("the " + count + " times try to acquire lock for taskid-list-lock......");
                    count++;
                    continue;
                }
                System.out.println("success to acquire lock for taskid-list-lock after " + count + " times try......");
                break;
            }
        }
    }

    /**
     * 释放掉一个分布式锁
     */
    public void releaseDistributedLock(){
        String path = "/taskid-list-lock";
        try {
            zooKeeper.delete(path,-1);
            System.out.println("release the lock for taskid-list-lock......");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getNodeData() {
        try {
            return new String(zooKeeper.getData("/taskid-list",false,new Stat()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public void setNodeData(String path ,String data){
        try {
            zooKeeper.setData(path,data.getBytes(),-1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 封装单例的静态内部类
     */
    private static class Singleton{
        private static ZookeeperSession instance;

        static {
            instance = new ZookeeperSession();
        }

        public static ZookeeperSession getInstance(){
            return instance;
        }
    }

    /**
     * 获取单例
     * @return
     */
    public static ZookeeperSession getInstance(){
        return Singleton.getInstance();
    }

    /**
     * 初始化单例的便捷方法
     */
    public static void init(){
        getInstance();
    }
}
