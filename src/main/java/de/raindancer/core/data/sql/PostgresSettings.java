package de.raindancer.core.data.sql;

import java.util.Properties;

/**
 * Where the network's central database is and how to log in to it.
 *
 * @param poolSize        connections this server keeps at most; every server of the network has
 *                        its own pool, so the database's {@code max_connections} has to cover the sum
 * @param applicationName what this server is called in {@code pg_stat_activity}
 * @param sslMode         libpq's {@code sslmode}: disable, prefer, require, verify-ca, verify-full
 */
public record PostgresSettings(String host, int port, String database, String user, String password,
                               int poolSize, String applicationName, String sslMode) {

    public static final int DEFAULT_POOL_SIZE = 10;

    public PostgresSettings {
        host = host == null || host.isBlank() ? "localhost" : host.trim();
        if (port <= 0 || port > 65535) {
            port = 5432;
        }
        database = database == null || database.isBlank() ? "rains" : database.trim();
        user = user == null ? "" : user.trim();
        password = password == null ? "" : password;
        poolSize = Math.clamp(poolSize, 2, 100);
        applicationName = applicationName == null || applicationName.isBlank() ? "RainsCore" : applicationName;
        sslMode = sslMode == null || sslMode.isBlank() ? "prefer" : sslMode.trim();
    }

    public static PostgresSettings of(String host, int port, String database, String user, String password) {
        return new PostgresSettings(host, port, database, user, password, DEFAULT_POOL_SIZE, null, null);
    }

    public PostgresSettings withPoolSize(int size) {
        return new PostgresSettings(host, port, database, user, password, size, applicationName, sslMode);
    }

    public PostgresSettings withApplicationName(String name) {
        return new PostgresSettings(host, port, database, user, password, poolSize, name, sslMode);
    }

    public PostgresSettings withSslMode(String mode) {
        return new PostgresSettings(host, port, database, user, password, poolSize, applicationName, mode);
    }

    public String url() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    /** Where it is, without the password — for log lines. */
    public String describe() {
        return user + "@" + host + ":" + port + "/" + database;
    }

    Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", applicationName);
        properties.setProperty("sslmode", sslMode);
        // A database that is down must not hold a server's thread for the driver's default of forever.
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("loginTimeout", "10");
        // A query that never comes back is cut off rather than holding its connection for good.
        properties.setProperty("socketTimeout", "120");
        properties.setProperty("tcpKeepAlive", "true");
        return properties;
    }

    @Override
    public String toString() {
        return "PostgresSettings[" + describe() + ", pool " + poolSize + ", ssl " + sslMode + "]";
    }
}
