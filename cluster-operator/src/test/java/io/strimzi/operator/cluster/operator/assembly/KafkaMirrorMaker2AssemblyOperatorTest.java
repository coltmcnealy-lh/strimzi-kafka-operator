/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.KafkaMirrorMaker2List;
import io.strimzi.api.kafka.model.KafkaJmxAuthenticationPasswordBuilder;
import io.strimzi.api.kafka.model.KafkaJmxOptionsBuilder;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2ClusterSpecBuilder;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2Resources;
import io.strimzi.api.kafka.model.status.KafkaMirrorMaker2Status;
import io.strimzi.operator.cluster.model.AuthenticationUtilsTest;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.KafkaMirrorMaker2Cluster;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.logging.LoggingModel;
import io.strimzi.operator.cluster.model.logging.LoggingUtils;
import io.strimzi.operator.cluster.model.metrics.MetricsModel;
import io.strimzi.operator.cluster.model.MockSharedEnvironmentProvider;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.model.SharedEnvironmentProvider;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.OrderedProperties;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.NetworkPolicyOperator;
import io.strimzi.operator.common.operator.resource.PodDisruptionBudgetOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.strimzi.operator.common.operator.resource.ServiceOperator;
import io.strimzi.operator.common.operator.resource.StrimziPodSetOperator;
import io.strimzi.platform.KubernetesVersion;
import io.strimzi.test.TestUtils;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaMirrorMaker2AssemblyOperatorTest {

    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();
    protected static Vertx vertx;
    private static final String METRICS_CONFIG = "{\"foo\":\"bar\"}";
    private static final String LOGGING_CONFIG = LoggingUtils.defaultLogConfig(Reconciliation.DUMMY_RECONCILIATION, "KafkaMirrorMaker2Cluster")
            .asPairsWithComment("Do not change this generated file. Logging can be configured in the corresponding Kubernetes resource.");

    private final KubernetesVersion kubernetesVersion = KubernetesVersion.V1_21;
    private final SharedEnvironmentProvider sharedEnvironmentProvider = new MockSharedEnvironmentProvider();

    @BeforeAll
    public static void before() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    public static void after() {
        vertx.close();
    }

    @Test
    public void testCreateCluster(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator<KubernetesClient, KafkaMirrorMaker2, KafkaMirrorMaker2List> mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";
        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);

        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));

        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(any(), anyString(), anyString(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture());
        when(mockDcOps.reconcile(any(), anyString(), anyString(), dcCaptor.capture())).thenReturn(Future.succeededFuture());
        when(mockDcOps.scaleUp(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.scaleDown(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));
        when(mockSecretOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(any(), anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<KafkaMirrorMaker2> mirrorMaker2Captor = ArgumentCaptor.forClass(KafkaMirrorMaker2.class);
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), mirrorMaker2Captor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(any(), anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));
        when(mockConnectClient.updateConnectLoggers(any(), anyString(), anyInt(), anyString(), any(OrderedProperties.class))).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"), x -> mockConnectClient);

        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);

        Checkpoint async = context.checkpoint();
        ops.reconcile(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name))
            .onComplete(context.succeeding(v -> context.verify(() -> {
                // No metrics config  => no CMs created
                Set<String> metricsNames = new HashSet<>();
                if (mirrorMaker2.metrics().isEnabled()) {
                    metricsNames.add(KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(kmm2Name));
                }

                // Verify service
                List<Service> capturedServices = serviceCaptor.getAllValues();
                assertThat(capturedServices, hasSize(2));
                Service service = capturedServices.get(0);
                assertThat(service.getMetadata().getName(), is(mirrorMaker2.getServiceName()));
                assertThat(service, is(mirrorMaker2.generateService()));
                Service headlessService = capturedServices.get(1);
                assertThat(headlessService, is(nullValue()));

                // Verify Deployment
                List<Deployment> capturedDc = dcCaptor.getAllValues();
                assertThat(capturedDc, hasSize(1));
                Deployment dc = capturedDc.get(0);
                assertThat(dc.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                Map<String, String> annotations = Map.of(
                        Annotations.ANNO_STRIMZI_LOGGING_APPENDERS_HASH, Util.hashStub(Util.getLoggingDynamicallyUnmodifiableEntries(LOGGING_CONFIG)),
                        Annotations.ANNO_STRIMZI_AUTH_HASH, "0"
                );
                assertThat(dc, is(mirrorMaker2.generateDeployment(3, null, annotations, true, null, null, null)));

                // Verify PodDisruptionBudget
                List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                assertThat(capturedPdb, hasSize(1));
                PodDisruptionBudget pdb = capturedPdb.get(0);
                assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                assertThat(pdb, is(mirrorMaker2.generatePodDisruptionBudget(false)));
                // Verify status
                KafkaMirrorMaker2Status capturedStatus = mirrorMaker2Captor.getAllValues().get(0).getStatus();
                assertThat(capturedStatus.getUrl(), is("http://foo-mirrormaker2-api.test.svc:8083"));
                assertThat(capturedStatus.getReplicas(), is(mirrorMaker2.getReplicas()));
                assertThat(capturedStatus.getLabelSelector(), is(mirrorMaker2.getSelectorLabels().toSelectorString()));
                assertThat(capturedStatus.getConditions().get(0).getStatus(), is("True"));
                assertThat(capturedStatus.getConditions().get(0).getType(), is("Ready"));
                async.flag();
            })));
    }

    @Test
    public void testUpdateClusterNoDiff(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator<KubernetesClient, KafkaMirrorMaker2, KafkaMirrorMaker2List> mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";

        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);
        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null)));
        when(mockDcOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(any(), eq(kmm2Namespace), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.reconcile(any(), eq(kmm2Namespace), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(any(), eq(kmm2Namespace), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture(), anyLong())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(any(), eq(kmm2Namespace), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(any(), anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(any(), anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));
        when(mockConnectClient.updateConnectLoggers(any(), anyString(), anyInt(), anyString(), any(OrderedProperties.class))).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name), kmm2)
            .onComplete(context.succeeding(v -> context.verify(() -> {

                // Verify service
                List<Service> capturedServices = serviceCaptor.getAllValues();
                assertThat(capturedServices, hasSize(2));

                // Verify Deployment Config
                List<Deployment> capturedDc = dcCaptor.getAllValues();
                assertThat(capturedDc, hasSize(1));

                // Verify scaleDown / scaleUp were not called
                assertThat(dcScaleDownNameCaptor.getAllValues(), hasSize(1));
                assertThat(dcScaleUpNameCaptor.getAllValues(), hasSize(1));

                // Verify PodDisruptionBudget
                List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                assertThat(capturedPdb, hasSize(1));
                PodDisruptionBudget pdb = capturedPdb.get(0);
                assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                assertThat("PodDisruptionBudgets are not equal", pdb, is(mirrorMaker2.generatePodDisruptionBudget(false)));

                async.flag();
            })));
    }

    @Test
    public void testUpdateCluster(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator<KubernetesClient, KafkaMirrorMaker2, KafkaMirrorMaker2List> mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";

        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);
        kmm2.getSpec().setImage("some/different:image"); // Change the image to generate some diff
        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null)));
        when(mockDcOps.get(kmm2Namespace, mirrorMaker2.getComponentName()))
                .thenReturn(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(any(), eq(kmm2Namespace), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.reconcile(any(), eq(kmm2Namespace), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(any(), eq(kmm2Namespace), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture(), anyLong())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(any(), eq(kmm2Namespace), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any()))
                .thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(any(), anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());

        // Mock CM get
        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        ConfigMap metricsCm = new ConfigMapBuilder().withNewMetadata()
                    .withName(KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(kmm2Name))
                    .withNamespace(kmm2Namespace)
                .endMetadata()
                .withData(Collections.singletonMap(MetricsModel.CONFIG_MAP_KEY, METRICS_CONFIG))
                .build();
        when(mockCmOps.get(kmm2Namespace, KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(kmm2Name))).thenReturn(metricsCm);

        ConfigMap loggingCm = new ConfigMapBuilder().withNewMetadata()
                    .withName(KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(kmm2Name))
                    .withNamespace(kmm2Namespace)
                    .endMetadata()
                    .withData(Collections.singletonMap(LoggingModel.LOG4J1_CONFIG_MAP_KEY, LOGGING_CONFIG))
                    .build();

        when(mockCmOps.get(kmm2Namespace, KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(kmm2Name))).thenReturn(metricsCm);

        // Mock CM patch
        Set<String> metricsCms = TestUtils.set();
        doAnswer(invocation -> {
            metricsCms.add(invocation.getArgument(1));
            return Future.succeededFuture();
        }).when(mockCmOps).reconcile(any(), eq(kmm2Namespace), anyString(), any());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(any(), anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));
        when(mockConnectClient.updateConnectLoggers(any(), anyString(), anyInt(), anyString(), any(OrderedProperties.class))).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name), kmm2)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                KafkaMirrorMaker2Cluster compareTo = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);

                // Verify service
                List<Service> capturedServices = serviceCaptor.getAllValues();
                assertThat(capturedServices, hasSize(2));
                Service service = capturedServices.get(0);
                assertThat(service.getMetadata().getName(), is(mirrorMaker2.getServiceName()));
                assertThat(service, is(mirrorMaker2.generateService()));
                Service headlessService = capturedServices.get(1);
                assertThat(headlessService, is(nullValue()));

                // Verify Deployment
                List<Deployment> capturedDc = dcCaptor.getAllValues();
                assertThat(capturedDc, hasSize(1));
                Deployment dc = capturedDc.get(0);
                assertThat(dc.getMetadata().getName(), is(compareTo.getComponentName()));
                Map<String, String> annotations = Map.of(
                        Annotations.ANNO_STRIMZI_LOGGING_APPENDERS_HASH, Util.hashStub(Util.getLoggingDynamicallyUnmodifiableEntries(loggingCm.getData().get(LoggingModel.LOG4J1_CONFIG_MAP_KEY))),
                        Annotations.ANNO_STRIMZI_AUTH_HASH, "0"
                );
                assertThat(dc, is(compareTo.generateDeployment(3, null, annotations, true, null, null, null)));

                // Verify PodDisruptionBudget
                List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                assertThat(capturedPdb, hasSize(1));
                PodDisruptionBudget pdb = capturedPdb.get(0);
                assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                assertThat(pdb, is(mirrorMaker2.generatePodDisruptionBudget(false)));

                // Verify scaleDown / scaleUp were not called
                assertThat(dcScaleDownNameCaptor.getAllValues(), hasSize(1));
                assertThat(dcScaleUpNameCaptor.getAllValues(), hasSize(1));

                // No metrics config  => no CMs created
                verify(mockCmOps, never()).createOrUpdate(any(), any());
                async.flag();
            })));
    }

    @Test
    public void testUpdateClusterWithFailingDeploymentFailure(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";

        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);
        kmm2.getSpec().setImage("some/different:image"); // Change the image to generate some diff

        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null)));
        when(mockDcOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> serviceNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(any(), serviceNamespaceCaptor.capture(), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.reconcile(any(), dcNamespaceCaptor.capture(), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.failedFuture("Failed"));

        ArgumentCaptor<String> dcScaleUpNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(any(), dcScaleUpNamespaceCaptor.capture(), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture(), anyLong())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(any(), dcScaleDownNamespaceCaptor.capture(), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockMirrorMaker2Ops.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new KafkaMirrorMaker2())));
        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockPdbOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new PodDisruptionBudget())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"));

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name), kmm2)
            .onComplete(context.failing(v -> async.flag()));
    }

    @Test
    public void testUpdateClusterScaleUp(VertxTestContext context) {
        final int scaleTo = 4;

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";

        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);
        kmm2.getSpec().setReplicas(scaleTo); // Change replicas to create ScaleUp

        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null)));
        when(mockDcOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockServiceOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());

        when(mockDcOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleUp(any(), eq(kmm2Namespace), eq(mirrorMaker2.getComponentName()), eq(scaleTo), anyLong());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleDown(any(), eq(kmm2Namespace), eq(mirrorMaker2.getComponentName()), eq(scaleTo), anyLong());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        when(mockMirrorMaker2Ops.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new KafkaMirrorMaker2())));
        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockPdbOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new PodDisruptionBudget())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(any(), anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));
        when(mockConnectClient.updateConnectLoggers(any(), anyString(), anyInt(), anyString(), any(OrderedProperties.class))).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name), kmm2)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                verify(mockDcOps).scaleUp(any(), eq(kmm2Namespace), eq(mirrorMaker2.getComponentName()), eq(scaleTo), anyLong());
                async.flag();
            })));
    }

    @Test
    public void testUpdateClusterScaleDown(VertxTestContext context) {
        int scaleTo = 2;

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";

        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);
        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);
        kmm2.getSpec().setReplicas(scaleTo); // Change replicas to create ScaleDown

        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), any(KafkaMirrorMaker2.class))).thenReturn(Future.succeededFuture());
        when(mockServiceOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateService());
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null)));
        when(mockDcOps.get(kmm2Namespace, mirrorMaker2.getComponentName())).thenReturn(mirrorMaker2.generateDeployment(3, null, new HashMap<>(), true, null, null, null));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockServiceOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());
        when(mockDcOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleUp(any(), eq(kmm2Namespace), eq(mirrorMaker2.getComponentName()), eq(scaleTo), anyLong());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleDown(any(), eq(kmm2Namespace), eq(mirrorMaker2.getComponentName()), eq(scaleTo), anyLong());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        when(mockMirrorMaker2Ops.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new KafkaMirrorMaker2())));
        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockPdbOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new PodDisruptionBudget())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(any(), anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));
        when(mockConnectClient.updateConnectLoggers(any(), anyString(), anyInt(), anyString(), any(OrderedProperties.class))).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"), x -> mockConnectClient);

        Checkpoint async = context.checkpoint();
        ops.createOrUpdate(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name), kmm2)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                verify(mockDcOps).scaleUp(any(), eq(kmm2Namespace), eq(mirrorMaker2.getComponentName()), eq(scaleTo), anyLong());
                async.flag();
            })));
    }

    @Test
    public void testReconcile(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator<KubernetesClient, KafkaMirrorMaker2, KafkaMirrorMaker2List> mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;

        String kmm2Namespace = "test";

        KafkaMirrorMaker2 foo = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, "foo");
        KafkaMirrorMaker2 bar = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, "bar");
        when(mockMirrorMaker2Ops.listAsync(eq(kmm2Namespace), isNull(LabelSelector.class))).thenReturn(Future.succeededFuture(asList(foo, bar)));
        // when requested ConfigMap for a specific Kafka MirrorMaker 2 cluster
        when(mockMirrorMaker2Ops.getAsync(eq(kmm2Namespace), eq("foo"))).thenReturn(Future.succeededFuture(foo));
        when(mockMirrorMaker2Ops.getAsync(eq(kmm2Namespace), eq("bar"))).thenReturn(Future.succeededFuture(bar));

        // providing the list of ALL Deployments for all the Kafka MirrorMaker 2 clusters
        Labels newLabels = Labels.forStrimziKind(KafkaMirrorMaker2.RESOURCE_KIND);
        when(mockDcOps.list(eq(kmm2Namespace), eq(newLabels))).thenReturn(
                asList(KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, bar, VERSIONS, sharedEnvironmentProvider).generateDeployment(3, null, new HashMap<>(), true, null, null, null)));

        // providing the list Deployments for already "existing" Kafka MirrorMaker 2 clusters
        Labels barLabels = Labels.forStrimziCluster("bar");
        when(mockDcOps.list(eq(kmm2Namespace), eq(barLabels))).thenReturn(
                asList(KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, bar, VERSIONS, sharedEnvironmentProvider).generateDeployment(3, null, new HashMap<>(), true, null, null, null))
        );
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());
        when(mockPdbOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());

        Set<String> createdOrUpdated = new CopyOnWriteArraySet<>();

        Checkpoint createOrUpdateAsync = context.checkpoint(2);
        //Must create all checkpoints used before flagging any to avoid premature test success
        Checkpoint async = context.checkpoint();

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities")) {

            @Override
            public Future<KafkaMirrorMaker2Status> createOrUpdate(Reconciliation reconciliation, KafkaMirrorMaker2 kafkaMirrorMaker2Assembly) {
                createdOrUpdated.add(kafkaMirrorMaker2Assembly.getMetadata().getName());
                createOrUpdateAsync.flag();
                return Future.succeededFuture();
            }
        };

        // Now try to reconcile all the Kafka MirrorMaker 2 clusters
        ops.reconcileAll("test", kmm2Namespace,
            context.succeeding(v -> context.verify(() -> {
                assertThat(createdOrUpdated, is(new HashSet<>(asList("foo", "bar"))));
                async.flag();
            })));

    }

    @Test
    public void testCreateClusterStatusNotReady(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator<KubernetesClient, KafkaMirrorMaker2, KafkaMirrorMaker2List> mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";
        String failureMsg = "failure";
        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);

        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));
        when(mockServiceOps.reconcile(any(), anyString(), anyString(), any())).thenReturn(Future.succeededFuture());
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture());
        when(mockDcOps.reconcile(any(), anyString(), anyString(), any())).thenReturn(Future.succeededFuture());
        when(mockDcOps.scaleUp(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.scaleDown(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.failedFuture(failureMsg));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));
        when(mockPdbOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<KafkaMirrorMaker2> mirrorMaker2Captor = ArgumentCaptor.forClass(KafkaMirrorMaker2.class);
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), mirrorMaker2Captor.capture())).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"));

        Checkpoint async = context.checkpoint();
        ops.reconcile(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name))
            .onComplete(context.failing(v -> context.verify(() -> {
                // Verify status
                List<KafkaMirrorMaker2> capturedMirrorMaker2s = mirrorMaker2Captor.getAllValues();
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getUrl(), is("http://foo-mirrormaker2-api.test.svc:8083"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getStatus(), is("True"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getType(), is("NotReady"));
                assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getMessage(), is(failureMsg));
                async.flag();
            })));
    }


    @Test
    public void testCreateClusterWithZeroReplicas(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator<KubernetesClient, KafkaMirrorMaker2, KafkaMirrorMaker2List> mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";
        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name, 0);

        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));

        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(any(), anyString(), anyString(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture());
        when(mockDcOps.reconcile(any(), anyString(), anyString(), dcCaptor.capture())).thenReturn(Future.succeededFuture());
        when(mockDcOps.scaleUp(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.scaleDown(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));
        when(mockSecretOps.reconcile(any(), eq(kmm2Namespace), any(), any())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(any(), anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<KafkaMirrorMaker2> mirrorMaker2Captor = ArgumentCaptor.forClass(KafkaMirrorMaker2.class);
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), mirrorMaker2Captor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(any(), anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));
        when(mockConnectClient.updateConnectLoggers(any(), anyString(), anyInt(), anyString(), any(OrderedProperties.class))).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"), x -> mockConnectClient);

        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);

        Checkpoint async = context.checkpoint();
        ops.reconcile(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name))
                .onComplete(context.succeeding(v -> context.verify(() -> {

                    // 0 Replicas - readiness should never get called.
                    verify(mockDcOps, never()).readiness(any(), anyString(), anyString(), anyLong(), anyLong());

                    // Verify service
                    List<Service> capturedServices = serviceCaptor.getAllValues();
                    assertThat(capturedServices, hasSize(2));
                    Service service = capturedServices.get(0);
                    assertThat(service.getMetadata().getName(), is(mirrorMaker2.getServiceName()));
                    assertThat(service, is(mirrorMaker2.generateService()));
                    Service headlessService = capturedServices.get(1);
                    assertThat(headlessService, is(nullValue()));

                    // Verify Deployment
                    List<Deployment> capturedDc = dcCaptor.getAllValues();
                    assertThat(capturedDc, hasSize(1));
                    Deployment dc = capturedDc.get(0);
                    assertThat(dc.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                    Map<String, String> annotations = Map.of(
                            Annotations.ANNO_STRIMZI_LOGGING_APPENDERS_HASH, Util.hashStub(Util.getLoggingDynamicallyUnmodifiableEntries(LOGGING_CONFIG)),
                            Annotations.ANNO_STRIMZI_AUTH_HASH, "0"
                    );
                    assertThat(dc, is(mirrorMaker2.generateDeployment(0, null, annotations, true, null, null, null)));

                    // Verify PodDisruptionBudget
                    List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                    assertThat(capturedPdb, hasSize(1));
                    PodDisruptionBudget pdb = capturedPdb.get(0);
                    assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                    assertThat(pdb, is(mirrorMaker2.generatePodDisruptionBudget(false)));

                    // Verify status
                    List<KafkaMirrorMaker2> capturedMirrorMaker2s = mirrorMaker2Captor.getAllValues();
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getUrl(), is(nullValue()));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getReplicas(), is(mirrorMaker2.getReplicas()));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getLabelSelector(), is(mirrorMaker2.getSelectorLabels().toSelectorString()));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getStatus(), is("True"));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getType(), is("Ready"));
                    async.flag();
                })));
    }

    @Test
    public void testCreateClusterWithJmxEnabled(VertxTestContext context) {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(true);
        CrdOperator<KubernetesClient, KafkaMirrorMaker2, KafkaMirrorMaker2List> mockMirrorMaker2Ops = supplier.mirrorMaker2Operator;
        DeploymentOperator mockDcOps = supplier.deploymentOperations;
        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        PodDisruptionBudgetOperator mockPdbOps = supplier.podDisruptionBudgetOperator;
        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        ServiceOperator mockServiceOps = supplier.serviceOperations;
        NetworkPolicyOperator mockNetPolOps = supplier.networkPolicyOperator;
        SecretOperator mockSecretOps = supplier.secretOperations;

        String kmm2Name = "foo";
        String kmm2Namespace = "test";
        KafkaMirrorMaker2 kmm2 = ResourceUtils.createEmptyKafkaMirrorMaker2(kmm2Namespace, kmm2Name);

        kmm2.getSpec().setJmxOptions(new KafkaJmxOptionsBuilder()
                .withAuthentication(new KafkaJmxAuthenticationPasswordBuilder().build())
                .build());

        when(mockMirrorMaker2Ops.get(kmm2Namespace, kmm2Name)).thenReturn(kmm2);
        when(mockMirrorMaker2Ops.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(kmm2));

        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(any(), anyString(), anyString(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<Deployment> dcCaptor = ArgumentCaptor.forClass(Deployment.class);
        when(mockDcOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture());
        when(mockDcOps.reconcile(any(), anyString(), anyString(), dcCaptor.capture())).thenReturn(Future.succeededFuture());
        when(mockDcOps.scaleUp(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.scaleDown(any(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.readiness(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockDcOps.waitForObserved(any(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockCmOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new ConfigMap())));
        when(mockNetPolOps.reconcile(any(), eq(kmm2.getMetadata().getNamespace()), eq(KafkaMirrorMaker2Resources.deploymentName(kmm2.getMetadata().getName())), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(new NetworkPolicy())));
        when(mockSecretOps.reconcile(any(), anyString(), any(), any())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.getAsync(anyString(), any())).thenReturn(Future.succeededFuture());

        when(mockPodSetOps.getAsync(any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<PodDisruptionBudget> pdbCaptor = ArgumentCaptor.forClass(PodDisruptionBudget.class);
        when(mockPdbOps.reconcile(any(), anyString(), any(), pdbCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<KafkaMirrorMaker2> mirrorMaker2Captor = ArgumentCaptor.forClass(KafkaMirrorMaker2.class);
        when(mockMirrorMaker2Ops.updateStatusAsync(any(), mirrorMaker2Captor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectApi mockConnectClient = mock(KafkaConnectApi.class);
        when(mockConnectClient.list(any(), anyString(), anyInt())).thenReturn(Future.succeededFuture(emptyList()));
        when(mockConnectClient.updateConnectLoggers(any(), anyString(), anyInt(), anyString(), any(OrderedProperties.class))).thenReturn(Future.succeededFuture());

        KafkaMirrorMaker2AssemblyOperator ops = new KafkaMirrorMaker2AssemblyOperator(vertx, new PlatformFeaturesAvailability(true, kubernetesVersion),
                supplier, ResourceUtils.dummyClusterOperatorConfig("-StableConnectIdentities"), x -> mockConnectClient);

        KafkaMirrorMaker2Cluster mirrorMaker2 = KafkaMirrorMaker2Cluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kmm2, VERSIONS, sharedEnvironmentProvider);

        Checkpoint async = context.checkpoint();
        ops.reconcile(new Reconciliation("test-trigger", KafkaMirrorMaker2.RESOURCE_KIND, kmm2Namespace, kmm2Name))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // No metrics config  => no CMs created
                    Set<String> metricsNames = new HashSet<>();
                    if (mirrorMaker2.metrics().isEnabled()) {
                        metricsNames.add(KafkaMirrorMaker2Resources.metricsAndLogConfigMapName(kmm2Name));
                    }

                    // Verify service
                    List<Service> capturedServices = serviceCaptor.getAllValues();
                    assertThat(capturedServices, hasSize(2));
                    Service service = capturedServices.get(0);
                    assertThat(service.getMetadata().getName(), is(mirrorMaker2.getServiceName()));
                    assertThat(service, is(mirrorMaker2.generateService()));
                    Service headlessService = capturedServices.get(1);
                    assertThat(headlessService, is(nullValue()));

                    // Verify Deployment
                    List<Deployment> capturedDc = dcCaptor.getAllValues();
                    assertThat(capturedDc, hasSize(1));
                    Deployment dc = capturedDc.get(0);
                    assertThat(dc.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                    Map<String, String> annotations = Map.of(
                            Annotations.ANNO_STRIMZI_LOGGING_APPENDERS_HASH, Util.hashStub(Util.getLoggingDynamicallyUnmodifiableEntries(LOGGING_CONFIG)),
                            Annotations.ANNO_STRIMZI_AUTH_HASH, "0"
                    );
                    assertThat(dc, is(mirrorMaker2.generateDeployment(3, null, annotations, true, null, null, null)));

                    // Verify PodDisruptionBudget
                    List<PodDisruptionBudget> capturedPdb = pdbCaptor.getAllValues();
                    assertThat(capturedPdb, hasSize(1));
                    PodDisruptionBudget pdb = capturedPdb.get(0);
                    assertThat(pdb.getMetadata().getName(), is(mirrorMaker2.getComponentName()));
                    assertThat(pdb, is(mirrorMaker2.generatePodDisruptionBudget(false)));

                    // Verify status
                    List<KafkaMirrorMaker2> capturedMirrorMaker2s = mirrorMaker2Captor.getAllValues();
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getUrl(), is("http://foo-mirrormaker2-api.test.svc:8083"));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getReplicas(), is(mirrorMaker2.getReplicas()));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getLabelSelector(), is(mirrorMaker2.getSelectorLabels().toSelectorString()));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getStatus(), is("True"));
                    assertThat(capturedMirrorMaker2s.get(0).getStatus().getConditions().get(0).getType(), is("Ready"));

                    // TODO: Test JMX properly
                    //assertThat(mirrorMaker2.isJmxEnabled(), is(true));
                    //assertThat(mirrorMaker2.isJmxAuthenticated(), is(true));
                    async.flag();
                })));
    }

    @Test
    public void testAddClusterToMirrorMaker2ConnectorConfigWithPlain() {
        var config = new HashMap<String, Object>();
        var prefix = "prefix";
        var cluster =
                new KafkaMirrorMaker2ClusterSpecBuilder(true)
                        .withAlias("sourceClusterAlias")
                        .withBootstrapServers("sourceClusterAlias.sourceNamespace.svc:9092")
                        .withNewKafkaClientAuthenticationPlain()
                            .withUsername("shaza")
                            .withNewPasswordSecret()
                                .withPassword("pa55word")
                            .endPasswordSecret()
                            .endKafkaClientAuthenticationPlain()
                        .build();

        KafkaMirrorMaker2AssemblyOperator.addClusterToMirrorMaker2ConnectorConfig(config, cluster, prefix);

        var jaasConfig = (String) config.remove("prefixsasl.jaas.config");
        var configEntry = AuthenticationUtilsTest.parseJaasConfig(jaasConfig);
        assertEquals("org.apache.kafka.common.security.plain.PlainLoginModule", configEntry.getLoginModuleName());
        assertEquals(Map.of(
                        "username", "shaza",
                        "password", "${file:/tmp/strimzi-mirrormaker2-connector.properties:sourceClusterAlias.sasl.password}"),
                configEntry.getOptions());

        assertEquals(new TreeMap<>(Map.of("prefixalias", "sourceClusterAlias",
                "prefixsecurity.protocol", "SASL_PLAINTEXT",
                "prefixsasl.mechanism", "PLAIN",
                "prefixbootstrap.servers", "sourceClusterAlias.sourceNamespace.svc:9092")),
                new TreeMap<>(config));
    }

    @Test
    public void testAddClusterToMirrorMaker2ConnectorConfigWithScram() {
        var config = new HashMap<String, Object>();
        var prefix = "prefix";
        var cluster =
                new KafkaMirrorMaker2ClusterSpecBuilder(true)
                        .withAlias("sourceClusterAlias")
                        .withBootstrapServers("sourceClusterAlias.sourceNamespace.svc:9092")
                        .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername("shaza")
                        .withNewPasswordSecret()
                        .withPassword("pa55word")
                        .endPasswordSecret()
                        .endKafkaClientAuthenticationScramSha512()
                        .build();

        KafkaMirrorMaker2AssemblyOperator.addClusterToMirrorMaker2ConnectorConfig(config, cluster, prefix);

        var jaasConfig = (String) config.remove("prefixsasl.jaas.config");
        var configEntry = AuthenticationUtilsTest.parseJaasConfig(jaasConfig);
        assertEquals("org.apache.kafka.common.security.scram.ScramLoginModule", configEntry.getLoginModuleName());
        assertEquals(Map.of(
                        "username", "shaza",
                        "password", "${file:/tmp/strimzi-mirrormaker2-connector.properties:sourceClusterAlias.sasl.password}"),
                configEntry.getOptions());

        assertEquals(new TreeMap<>(Map.of("prefixalias", "sourceClusterAlias",
                        "prefixsecurity.protocol", "SASL_PLAINTEXT",
                        "prefixsasl.mechanism", "SCRAM-SHA-512",
                        "prefixbootstrap.servers", "sourceClusterAlias.sourceNamespace.svc:9092")),
                new TreeMap<>(config));
    }

    @Test
    public void testAddClusterToMirrorMaker2ConnectorConfigWithOauth() {
        var config = new HashMap<String, Object>();
        var prefix = "prefix";
        var cluster =
                new KafkaMirrorMaker2ClusterSpecBuilder(true)
                        .withAlias("sourceClusterAlias")
                        .withBootstrapServers("sourceClusterAlias.sourceNamespace.svc:9092")
                        .withNewKafkaClientAuthenticationOAuth()
                            .withNewAccessToken()
                                .withKey("accessTokenKey")
                                .withSecretName("accessTokenSecretName")
                            .endAccessToken()
                            .withNewClientSecret()
                                .withKey("clientSecretKey")
                                .withSecretName("clientSecretSecretName")
                            .endClientSecret()
                            .withNewPasswordSecret()
                                .withPassword("passwordSecretPassword")
                                .withSecretName("passwordSecretSecretName")
                            .endPasswordSecret()
                            .withNewRefreshToken()
                                .withKey("refreshTokenKey")
                                .withSecretName("refreshTokenSecretName")
                            .endRefreshToken()
                        .endKafkaClientAuthenticationOAuth()
                        .build();

        KafkaMirrorMaker2AssemblyOperator.addClusterToMirrorMaker2ConnectorConfig(config, cluster, prefix);

        var jaasConfig = (String) config.remove("prefixsasl.jaas.config");
        var configEntry = AuthenticationUtilsTest.parseJaasConfig(jaasConfig);
        assertEquals("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule", configEntry.getLoginModuleName());
        assertEquals(Map.of(
                        "oauth.client.secret", "${file:/tmp/strimzi-mirrormaker2-connector.properties:sourceClusterAlias.oauth.client.secret}",
                        "oauth.access.token", "${file:/tmp/strimzi-mirrormaker2-connector.properties:sourceClusterAlias.oauth.access.token}",
                        "oauth.refresh.token", "${file:/tmp/strimzi-mirrormaker2-connector.properties:sourceClusterAlias.oauth.refresh.token}",
                        "oauth.password.grant.password", "${file:/tmp/strimzi-mirrormaker2-connector.properties:sourceClusterAlias.oauth.password.grant.password}"),
                configEntry.getOptions());

        assertEquals(Map.of(
                "prefixalias", "sourceClusterAlias",
                "prefixbootstrap.servers", "sourceClusterAlias.sourceNamespace.svc:9092",
                "prefixsasl.login.callback.handler.class", "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler",
                "prefixsasl.mechanism", "OAUTHBEARER",
                "prefixsecurity.protocol", "SASL_PLAINTEXT"),
                config);
    }
}
