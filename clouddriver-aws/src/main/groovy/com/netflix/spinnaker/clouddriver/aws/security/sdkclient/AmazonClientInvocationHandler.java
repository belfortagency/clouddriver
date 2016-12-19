/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.security.sdkclient;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.services.autoscaling.model.*;
import com.amazonaws.services.cloudwatch.model.DescribeAlarmsRequest;
import com.amazonaws.services.cloudwatch.model.DescribeAlarmsResult;
import com.amazonaws.services.cloudwatch.model.MetricAlarm;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult;
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.clouddriver.aws.security.EddaTimeoutConfig;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AmazonClientInvocationHandler implements InvocationHandler {

  private static final Logger log = LoggerFactory.getLogger(AmazonClientInvocationHandler.class);

  public static final ThreadLocal<Long> lastModified = new ThreadLocal<>();

  private final String edda;
  private final HttpClient httpClient;
  private final Object delegate;
  private final String serviceName;
  private final ObjectMapper objectMapper;
  private final EddaTimeoutConfig eddaTimeoutConfig;
  private final Registry registry;
  private final Map<String, String> metricTags;

  public AmazonClientInvocationHandler(Object delegate,
                                       String serviceName,
                                       String edda,
                                       HttpClient httpClient,
                                       ObjectMapper objectMapper,
                                       EddaTimeoutConfig eddaTimeoutConfig,
                                       Registry registry,
                                       Map<String, String> metricTags) {
    this.edda = edda;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.delegate = delegate;
    this.serviceName = serviceName;
    this.eddaTimeoutConfig = eddaTimeoutConfig == null ? EddaTimeoutConfig.DEFAULT : eddaTimeoutConfig;
    this.registry = registry;
    this.metricTags = ImmutableMap.copyOf(metricTags);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      Method thisMethod = this.getClass().getMethod(method.getName(), args != null && args.length > 0 ?
        getClassArgs(args) : new Class[0]);
      return thisMethod.invoke(this, args);
    } catch (NoSuchMethodException e) {
      return method.invoke(delegate, args);
    }
  }

  static Class[] getClassArgs(Object[] args) {
    List<Class> classes = new ArrayList<>();
    for (Object object : args) {
      classes.add(object.getClass());
    }
    return classes.toArray(new Class[classes.size()]);
  }

  ////////////////////////////////////
  //
  // AmazonAutoScaling
  //
  ////////////////////////////////////
  public DescribeAutoScalingGroupsResult describeAutoScalingGroups() {
    return describeAutoScalingGroups(null);
  }

  public DescribeAutoScalingGroupsResult describeAutoScalingGroups(DescribeAutoScalingGroupsRequest request) {
    return new DescribeAutoScalingGroupsResult()
      .withAutoScalingGroups(
        describe(request, "autoScalingGroupNames", "autoScalingGroups", AutoScalingGroup.class));
  }

  ////////////////////////////////////
  //
  // AmazonCloudWatch
  //
  ////////////////////////////////////
  public DescribeAlarmsResult describeAlarms() {
    return describeAlarms(null);
  }

  public DescribeAlarmsResult describeAlarms(DescribeAlarmsRequest request) {
    return new DescribeAlarmsResult()
      .withMetricAlarms(
        describe(request, "alarmNames", "alarms", MetricAlarm.class));
  }

  public DescribeScheduledActionsResult describeScheduledActions() {
    return describeScheduledActions(null);
  }

  public DescribeScheduledActionsResult describeScheduledActions(DescribeScheduledActionsRequest request) {
    return new DescribeScheduledActionsResult()
      .withScheduledUpdateGroupActions(
        describe(request, "scheduledActionNames", "scheduledActions", ScheduledUpdateGroupAction.class));
  }

  public DescribePoliciesResult describePolicies() {
    return describePolicies(null);
  }

  public DescribePoliciesResult describePolicies(DescribePoliciesRequest request) {
    return new DescribePoliciesResult()
      .withScalingPolicies(
        describe(request, "policyNames", "scalingPolicies", ScalingPolicy.class));
  }

  ////////////////////////////////////
  //
  // AmazonEC2
  //
  ////////////////////////////////////
  public DescribeImagesResult describeImages() {
    return describeImages(null);
  }

  public DescribeImagesResult describeImages(DescribeImagesRequest request) {
    return new DescribeImagesResult()
      .withImages(
        describe(request, "imageIds", "images", Image.class));
  }

  public DescribeInstancesResult describeInstances() {
    return describeInstances(null);
  }

  public DescribeInstancesResult describeInstances(DescribeInstancesRequest request) {
    return new DescribeInstancesResult()
      .withReservations(new Reservation()
        .withReservationId("1234")
        .withInstances(
          describe(request, "instanceIds", "../view/instances", Instance.class)));
  }

  public DescribeLaunchConfigurationsResult describeLaunchConfigurations() {
    return describeLaunchConfigurations(null);
  }

  public DescribeLaunchConfigurationsResult describeLaunchConfigurations(DescribeLaunchConfigurationsRequest request) {
    return new DescribeLaunchConfigurationsResult()
      .withLaunchConfigurations(
        describe(request, "launchConfigurationNames", "launchConfigurations", LaunchConfiguration.class));
  }

  public DescribeReservedInstancesOfferingsResult describeReservedInstancesOfferings() {
    return describeReservedInstancesOfferings(null);
  }

  public DescribeReservedInstancesOfferingsResult describeReservedInstancesOfferings(DescribeReservedInstancesOfferingsRequest request) {
    return new DescribeReservedInstancesOfferingsResult()
      .withReservedInstancesOfferings(
        describe(request, "reservedInstancesOfferingIds", "reservedInstancesOfferings", ReservedInstancesOffering.class));
  }

  public DescribeSecurityGroupsResult describeSecurityGroups() {
    return describeSecurityGroups(null);
  }

  public DescribeSecurityGroupsResult describeSecurityGroups(DescribeSecurityGroupsRequest request) {
    return new DescribeSecurityGroupsResult()
      .withSecurityGroups(
        describe(request, "groupIds", "securityGroups", SecurityGroup.class));
  }

  public DescribeSubnetsResult describeSubnets() {
    return describeSubnets(null);
  }

  public DescribeSubnetsResult describeSubnets(DescribeSubnetsRequest request) {
    return new DescribeSubnetsResult()
      .withSubnets(
        describe(request, "subnetIds", "subnets", Subnet.class));
  }

  public DescribeVpcsResult describeVpcs() {
    return describeVpcs(null);
  }

  public DescribeVpcsResult describeVpcs(DescribeVpcsRequest request) {
    return new DescribeVpcsResult()
      .withVpcs(
        describe(request, "vpcIds", "vpcs", Vpc.class));
  }

  public DescribeVpcClassicLinkResult describeVpcClassicLink() {
    return describeVpcClassicLink(null);
  }

  public DescribeVpcClassicLinkResult describeVpcClassicLink(DescribeVpcClassicLinkRequest request) {
    return new DescribeVpcClassicLinkResult()
      .withVpcs(
        describe(request, "vpcIds", "vpcClassicLinks", VpcClassicLink.class));
  }

  public DescribeClassicLinkInstancesResult describeClassicLinkInstances() {
    return describeClassicLinkInstances(null);
  }

  public DescribeClassicLinkInstancesResult describeClassicLinkInstances(DescribeClassicLinkInstancesRequest request) {
    return new DescribeClassicLinkInstancesResult()
      .withInstances(
        describe(request, "instanceIds", "classicLinkInstances", ClassicLinkInstance.class));
  }

  ////////////////////////////////////
  //
  // AmazonElasticLoadBalancing
  //
  ////////////////////////////////////
  public DescribeLoadBalancersResult describeLoadBalancers() {
    return describeLoadBalancers(null);
  }

  public DescribeLoadBalancersResult describeLoadBalancers(DescribeLoadBalancersRequest request) {
    return new DescribeLoadBalancersResult()
      .withLoadBalancerDescriptions(
        describe(request, "loadBalancerNames", "loadBalancers", LoadBalancerDescription.class));
  }

  ////////////////////////////////////
  private <T> List<T> describe(AmazonWebServiceRequest request, String idKey, final String object, final Class<T> singleType) {
    lastModified.set(null);
    try {
      JavaType singleMeta = objectMapper.getTypeFactory().constructParametrizedType(Metadata.class, Metadata.class, singleType);
      Collection<String> ids = getRequestIds(request, idKey);
      Long mtime = null;
      List<T> results = new ArrayList<>();
      if (ids.isEmpty()) {
        final byte[] json = getJson(object, null);
        JavaType listMeta = objectMapper.getTypeFactory().constructParametrizedType(List.class, List.class, singleMeta);
        List<Metadata<T>> metadataResults = objectMapper.readValue(json, listMeta);
        for (Metadata<T> meta : metadataResults) {
          mtime = mtime == null ? meta.mtime : Math.min(mtime, meta.mtime);
          results.add(meta.data);
        }
      } else {
        for (String id : ids) {
          final byte[] json = getJson(object, id);
          Metadata<T> result = objectMapper.readValue(json, singleMeta);
          mtime = mtime == null ? result.mtime : Math.min(mtime, result.mtime);
          results.add(result.data);
        }
      }
      lastModified.set(mtime);
      return results;
    } catch (Exception e) {
      AmazonServiceException ex = new AmazonServiceException("400 Bad Request -- Edda could not find one of the managed objects requested.", e);
      ex.setStatusCode(400);
      ex.setServiceName(serviceName);
      ex.setErrorType(AmazonServiceException.ErrorType.Unknown);
      throw ex;
    }
  }

  private static Collection<String> getRequestIds(AmazonWebServiceRequest request, String idFieldName) {
    if (request == null) {
      return Collections.emptySet();
    }
    try {
      Field field = request.getClass().getDeclaredField(idFieldName);
      field.setAccessible(true);
      Collection<String> collection = (Collection<String>) field.get(request);
      return collection == null ? Collections.emptySet() : collection;
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private byte[] getJson(String objectName, String key) throws IOException {
    final String url = edda + "/REST/v2/aws/" + objectName + (key == null ? ";_expand" : "/" + key) + ";_meta";
    final Map<String, String> metricTags = new HashMap<>(this.metricTags);
    metricTags.put("collection", objectName);
    metricTags.put("collectionReadType", key == null ? "full" : "single");

    HttpGet get = new HttpGet(url);
    get.setConfig(RequestConfig.custom().setConnectTimeout(eddaTimeoutConfig.getConnectTimeout()).setSocketTimeout(eddaTimeoutConfig.getSocketTimeout()).build());

    long retryDelay = eddaTimeoutConfig.getRetryBase();
    int retryAttempts = 0;
    String lastException = "";
    Random r = new Random();
    Exception ex;
    while (retryAttempts < eddaTimeoutConfig.getMaxAttempts()) {
      ex = null;
      HttpEntity entity = null;
      long startTime = System.nanoTime();
      int httpStatusCode = 0;
      try {
        HttpResponse response = httpClient.execute(get);
        entity = response.getEntity();
        httpStatusCode = response.getStatusLine().getStatusCode();
        if (httpStatusCode != HttpStatus.SC_OK) {
          lastException = response.getProtocolVersion().toString() + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase();
        } else {
          long httpReadStart = System.nanoTime();
          try {
            return getBytesFromInputStream(entity.getContent(), entity.getContentLength());
          } finally {
            registry.timer(registry.createId("edda.httpReadTime", metricTags)).record(System.nanoTime() - httpReadStart, TimeUnit.NANOSECONDS);
          }
        }
      } catch (IOException ioe) {
        lastException = ioe.getClass().getSimpleName() + ": " + ioe.getMessage();
        ex = ioe;
      } finally {
        String classification = lastException == null ? "SUCCESS" : (ex == null ? "HTTP_FAILURE" : "IOException");
        Id httpTime = registry.createId("edda.httpRequestTime", metricTags).withTag("httpStatus", Integer.toString(httpStatusCode)).withTag("classification", classification);
        registry.timer(httpTime).record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        EntityUtils.consume(entity);
      }
      final String exceptionFormat = "Edda request {} failed with {}";
      if (ex == null) {
        log.warn(exceptionFormat, url, lastException);
      } else {
        log.warn(exceptionFormat, url, lastException, ex);
      }
      registry.counter(registry.createId("edda.retryDelayMillis", metricTags)).increment(retryDelay);
      try {
        Thread.sleep(retryDelay);
      } catch (InterruptedException inter) {
        break;
      }
      registry.counter(registry.createId("edda.retries", metricTags)).increment();
      retryAttempts++;
      retryDelay += r.nextInt(eddaTimeoutConfig.getBackoffMillis());
    }
    throw new IOException(lastException);
  }

  private static byte[] getBytesFromInputStream(InputStream is, long contentLength) throws IOException {
    final int bufLen = contentLength < 0 || contentLength > Integer.MAX_VALUE ? 128 * 1024 : (int) contentLength;
    final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufLen);
    final byte[] buf = new byte[16 * 1024];
    int bytesRead;
    while ((bytesRead = is.read(buf)) != -1) {
      baos.write(buf, 0, bytesRead);
    }
    is.close();
    return baos.toByteArray();
  }

  private static class Metadata<T> {
    final Long mtime;
    final T data;

    @JsonCreator
    public Metadata(@JsonProperty("mtime") Long mtime,
                    @JsonProperty("data") T data) {
      this.mtime = mtime;
      this.data = data;
    }
  }

}
