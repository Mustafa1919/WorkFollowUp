package com.app.tracker.core.datasource;

import com.app.tracker.core.config.TransactionManagementConfig;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link ReadReplica} bayragini transaction'dan ONCE kurar (transaction advisor'inden daha yuksek
 * oncelik) ve transaction bittikten SONRA temizler. Gercek baglanti secimi
 * LazyConnectionDataSourceProxy sayesinde transaction basladiktan sonra, ilk SQL'de yapilir; bu
 * siralama yine de bayragin transaction omru boyunca yerinde kalmasini garanti eder.
 */
@Aspect
@Component
@Order(TransactionManagementConfig.ORDER - 1)
public class ReadReplicaAspect {

  @Around(
      "@within(com.app.tracker.core.datasource.ReadReplica) || "
          + "@annotation(com.app.tracker.core.datasource.ReadReplica)")
  public Object routeToReplica(ProceedingJoinPoint joinPoint) throws Throwable {
    Boolean previous = ReadReplicaContext.activate();
    try {
      return joinPoint.proceed();
    } finally {
      ReadReplicaContext.restore(previous);
    }
  }
}
