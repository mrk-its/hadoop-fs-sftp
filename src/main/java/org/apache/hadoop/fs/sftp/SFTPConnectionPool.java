/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs.sftp;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.util.StringUtils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/** Concurrent/Multiple Connections. */
class SFTPConnectionPool {

    public static final Log LOG = LogFactory.getLog(SFTPFileSystem.class);
    // Maximum number of allowed live connections. This doesn't mean we cannot
    // have more live connections. It means that when we have more
    // live connections than this threshold, any unused connection will be
    // closed.
    private int maxConnection;
    private int liveConnectionCount = 0;
    private HashMap<ConnectionInfo, HashSet<ChannelSftp>> idleConnections =
            new HashMap<ConnectionInfo, HashSet<ChannelSftp>>();
    private HashMap<ChannelSftp, ConnectionInfo> con2infoMap =
            new HashMap<ChannelSftp, ConnectionInfo>();

    SFTPConnectionPool(int maxConnection) {
        this.maxConnection = maxConnection;
    }

    synchronized ChannelSftp getFromPool(ConnectionInfo info) throws IOException {
        Set<ChannelSftp> cons = idleConnections.get(info);
        ChannelSftp channel;

        if (cons != null && cons.size() > 0) {
            Iterator<ChannelSftp> it = cons.iterator();
            if (it.hasNext()) {
                channel = it.next();
                idleConnections.remove(info);
                return channel;
            } else {
                throw new IOException("Connection pool error.");
            }
        }
        return null;
    }

    /** Add the channel into pool.
     * @param channel
     */
    synchronized void returnToPool(ChannelSftp channel) {
        ConnectionInfo info = con2infoMap.get(channel);
        HashSet<ChannelSftp> cons = idleConnections.get(info);
        if (cons == null) {
            cons = new HashSet<ChannelSftp>();
            idleConnections.put(info, cons);
        }
        cons.add(channel);

    }

    /** Shutdown the connection pool and close all open connections. */
    synchronized void shutdown() {
        if (this.con2infoMap == null){
            return; // already shutdown in case it is called
        }
        LOG.info("Inside shutdown, con2infoMap size=" + con2infoMap.size());

        this.maxConnection = 0;
        Set<ChannelSftp> cons = con2infoMap.keySet();
        if (cons != null && cons.size() > 0) {
            // make a copy since we need to modify the underlying Map
            Set<ChannelSftp> copy = new HashSet<ChannelSftp>(cons);
            // Initiate disconnect from all outstanding connections
            for (ChannelSftp con : copy) {
                try {
                    disconnect(con);
                } catch (IOException ioe) {
                    ConnectionInfo info = con2infoMap.get(con);
                    LOG.error(
                            "Error encountered while closing connection to " + info.getHost(),
                            ioe);
                }
            }
        }
        // make sure no further connections can be returned.
        this.idleConnections = null;
        this.con2infoMap = null;
    }

    public synchronized int getMaxConnection() {
        return maxConnection;
    }

    public synchronized void setMaxConnection(int maxConn) {
        this.maxConnection = maxConn;
    }

    private ChannelSftp getChannelFromPool(String host, int port, String user) throws IOException {
        // get connection from pool
        ConnectionInfo info = new ConnectionInfo(host, port, user);
        ChannelSftp channel = getFromPool(info);

        if (channel != null) {
            if (channel.isConnected()) {
                return channel;
            } else {
                channel = null;
                synchronized (this) {
                    --liveConnectionCount;
                    con2infoMap.remove(channel);
                }
            }
        }
        return null;
    }



    private JSch createClientWithKeyFile(String user, String password, String keyFile) throws JSchException {
        JSch jsch = new JSch();
        if(keyFile != null && keyFile.length() > 0) {
            jsch.addIdentity(keyFile);
        }
        return jsch;
    }

    private JSch createClientWithKeyBytes(String user, String password, byte[] keyBytes) throws JSchException {
        JSch jsch = new JSch();
        if(keyBytes != null && keyBytes.length > 0) {
            jsch.addIdentity("", keyBytes, null, null);
        }
        return jsch;
    }

    private Session connectClient(JSch jschClient, String host, int port, String user, String password, java.util.Properties config) throws JSchException {
        Session session = null;

        if (user == null || user.length() == 0) {
            user = System.getProperty("user.name");
        }

        if (password == null) {
            password = "";
        }


        int attempt = 0;
        int maxAttempt = 3;
        while (attempt <= maxAttempt) {
            try {
                if (port <= 0) {
                    session = jschClient.getSession(user, host);
                } else {
                    session = jschClient.getSession(user, host, port);
                }

                if(password != "") {
                    session.setPassword(password);
                }

                session.setConfig(config);
                    /*
                        Ugly fix for the verify:false error Jsch throws when you make thousands of connections.
                        https://sourceforge.net/p/jsch/bugs/80/
                        Blindly retry till three times and throw up after.
                    */

                ++attempt;
                session.connect();
                attempt = maxAttempt + 1;
            } catch (JSchException e) {
                if (!e.getMessage().equalsIgnoreCase("verify: false") || attempt > maxAttempt) {
                    throw e;
                }
                if (session != null) {
                    session.disconnect();
                    session = null;
                }
                LOG.info(
                        "session.connect() threw a verify:false exception. Going for another attempt. Attempt: " + attempt
                );
                //else pass. Do nothing and let the while repeat
            }
        }
        return session;
    }

    public ChannelSftp connect(String host, int port, String user, String password, byte[] publicKey, java.util.Properties config) throws IOException {
        ConnectionInfo info = new ConnectionInfo(host, port, user);
        try {
            ChannelSftp channel = getChannelFromPool(host, port, user);
            if(channel != null) {
                return channel;
            }

            JSch jsch = this.createClientWithKeyBytes(user, password, publicKey);
            Session session = this.connectClient(jsch, host, port, user, password, config);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            synchronized (this) {
                con2infoMap.put(channel, info);
                liveConnectionCount++;
            }

            return channel;

        } catch (JSchException e) {
            throw new IOException(StringUtils.stringifyException(e));
        }
    }

    public ChannelSftp connect(String host, int port, String user,
                               String password, String keyFile, java.util.Properties config) throws IOException {
        ConnectionInfo info = new ConnectionInfo(host, port, user);
        try {
            ChannelSftp channel = getChannelFromPool(host, port, user);
            if(channel != null) {
                return channel;
            }

            JSch jsch = this.createClientWithKeyFile(user, password, keyFile);
            Session session = this.connectClient(jsch, host, port, user, password, config);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();
            synchronized (this) {
                con2infoMap.put(channel, info);
                liveConnectionCount++;
            }

            return channel;

            } catch (JSchException e) {
            throw new IOException(StringUtils.stringifyException(e));
        }
    }

    void disconnect(ChannelSftp channel) throws IOException {
        if (channel != null) {
            // close connection if too many active connections
            boolean closeConnection = false;
            synchronized (this) {
                if (liveConnectionCount > maxConnection) {
                    --liveConnectionCount;
                    con2infoMap.remove(channel);
                    closeConnection = true;
                }
            }
            if (closeConnection) {
                if (channel.isConnected()) {
                    try {
                        Session session = channel.getSession();
                        channel.disconnect();
                        session.disconnect();
                    } catch (JSchException e) {
                        throw new IOException(StringUtils.stringifyException(e));
                    }
                }

            } else {
                returnToPool(channel);
            }
        }
    }

    public int getIdleCount() {
        return this.idleConnections.size();
    }

    public int getLiveConnCount() {
        return this.liveConnectionCount;
    }

    public int getConnPoolSize() {
        return this.con2infoMap.size();
    }

    /**
     * Class to capture the minimal set of information that distinguish
     * between different connections.
     */
    static class ConnectionInfo {
        private String host = "";
        private int port;
        private String user = "";

        ConnectionInfo(String hst, int prt, String usr) {
            this.host = hst;
            this.port = prt;
            this.user = usr;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String hst) {
            this.host = hst;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int prt) {
            this.port = prt;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String usr) {
            this.user = usr;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj instanceof ConnectionInfo) {
                ConnectionInfo con = (ConnectionInfo) obj;

                boolean ret = true;
                if (this.host == null || !this.host.equalsIgnoreCase(con.host)) {
                    ret = false;
                }
                if (this.port >= 0 && this.port != con.port) {
                    ret = false;
                }
                if (this.user == null || !this.user.equalsIgnoreCase(con.user)) {
                    ret = false;
                }
                return ret;
            } else {
                return false;
            }

        }

        @Override
        public int hashCode() {
            int hashCode = 0;
            if (host != null) {
                hashCode += host.hashCode();
            }
            hashCode += port;
            if (user != null) {
                hashCode += user.hashCode();
            }
            return hashCode;
        }

    }
}
