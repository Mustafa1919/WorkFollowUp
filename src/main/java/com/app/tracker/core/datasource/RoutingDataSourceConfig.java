package com.app.tracker.core.datasource;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Yazma + (opsiyonel) read havuzunu kurar ve tek {@code DataSource} olarak sunar. Boot'un otomatik
 * DataSource'u bu bean varken devreye girmez; {@code spring.datasource.*} ve {@code
 * spring.datasource.hikari.*} ayarlari yazma havuzu icin AYNEN gecerlidir.
 *
 * <p>Havuzlar bilerek ayri {@code DataSource} bean'i DEGIL: aksi halde actuator {@code db} saglik
 * gostergesi read havuzunu da readiness'e katardi ve bir replica arizasi tum pod'lari trafikten
 * cikarirdi — oysa yalnizca analitik endpoint'leri etkilenir.
 *
 * <p>Neden {@link LazyConnectionDataSourceProxy}: yonlendirme karari transaction'in {@code
 * readOnly} bayragina bakar; baglanti transaction baslarken degil ilk SQL'de alinirsa bayrak o ana
 * kadar kesinlesmis olur ve {@code TenancyGuardAspect}'in {@code set_config} sorgusu da dogru
 * havuza gider.
 *
 * <p>{@code migrate} profilinde devre disidir: Flyway job'i Boot'un standart tek havuzunu kullanir.
 */
@Configuration
@Profile("!migrate")
@EnableConfigurationProperties(ReadReplicaProperties.class)
public class RoutingDataSourceConfig {

  static final String WRITE = "write";
  static final String READ = "read";

  @Bean
  public DataSource dataSource(
      DataSourceProperties writeProperties,
      ReadReplicaProperties readProperties,
      Environment environment) {
    HikariDataSource write = newPool(writeProperties, "tracker-write", environment);
    if (!readProperties.isConfigured()) {
      return new PooledRoutingDataSource(new ReadWriteRouter(write, write), List.of(write));
    }

    HikariDataSource read = newPool(writeProperties, "tracker-read", environment);
    read.setJdbcUrl(readProperties.url());
    if (readProperties.username() != null && !readProperties.username().isBlank()) {
      read.setUsername(readProperties.username());
    }
    if (readProperties.password() != null && !readProperties.password().isBlank()) {
      read.setPassword(readProperties.password());
    }
    if (readProperties.maximumPoolSize() != null) {
      read.setMaximumPoolSize(readProperties.maximumPoolSize());
    }
    return new PooledRoutingDataSource(new ReadWriteRouter(write, read), List.of(write, read));
  }

  private static HikariDataSource newPool(
      DataSourceProperties properties, String poolName, Environment environment) {
    HikariDataSource pool =
        properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    // spring.datasource.hikari.* (Boot'un otomatik havuzuyla ayni kaynak) her iki havuza uygulanir.
    Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
    pool.setPoolName(poolName);
    return pool;
  }

  /**
   * Karar: {@link ReadReplica} isareti VE readOnly transaction ikisi birden varsa read, aksi halde
   * yazma. Transaction disi (baslangic, health check) ve yazma transaction'lari daima yazmaya
   * gider.
   */
  static final class ReadWriteRouter extends AbstractRoutingDataSource {

    ReadWriteRouter(DataSource write, DataSource read) {
      setTargetDataSources(Map.<Object, Object>of(WRITE, write, READ, read));
      setDefaultTargetDataSource(write);
      afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
      return ReadReplicaContext.isActive()
              && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
          ? READ
          : WRITE;
    }
  }

  /** Havuzlari uygulama kapanirken kapatir (havuzlar bean olmadigi icin container kapatmaz). */
  static final class PooledRoutingDataSource extends LazyConnectionDataSourceProxy
      implements AutoCloseable {

    private final List<HikariDataSource> pools;

    PooledRoutingDataSource(DataSource router, List<HikariDataSource> pools) {
      super(router);
      this.pools = pools.stream().distinct().toList();
    }

    @Override
    public void close() {
      pools.forEach(HikariDataSource::close);
    }
  }
}
