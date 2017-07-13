/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos;

import ch.vorburger.mariadb4j.DB;
import com.google.gson.Gson;
import io.mifos.accounting.api.v1.client.LedgerManager;
import io.mifos.accounting.api.v1.domain.Account;
import io.mifos.accounting.api.v1.domain.AccountType;
import io.mifos.accounting.api.v1.domain.Ledger;
import io.mifos.accounting.importer.AccountImporter;
import io.mifos.accounting.importer.LedgerImporter;
import io.mifos.anubis.api.v1.domain.AllowedOperation;
import io.mifos.core.api.config.EnableApiFactory;
import io.mifos.core.api.context.AutoGuest;
import io.mifos.core.api.context.AutoSeshat;
import io.mifos.core.api.context.AutoUserContext;
import io.mifos.core.api.util.ApiConstants;
import io.mifos.core.api.util.ApiFactory;
import io.mifos.core.lang.TenantContextHolder;
import io.mifos.core.test.env.ExtraProperties;
import io.mifos.core.test.env.TestEnvironment;
import io.mifos.core.test.listener.EventRecorder;
import io.mifos.core.test.servicestarter.ActiveMQForTest;
import io.mifos.core.test.servicestarter.EurekaForTest;
import io.mifos.core.test.servicestarter.IntegrationTestEnvironment;
import io.mifos.core.test.servicestarter.Microservice;
import io.mifos.identity.api.v1.client.IdentityManager;
import io.mifos.identity.api.v1.domain.*;
import io.mifos.identity.api.v1.events.ApplicationPermissionEvent;
import io.mifos.identity.api.v1.events.ApplicationPermissionUserEvent;
import io.mifos.identity.api.v1.events.ApplicationSignatureEvent;
import io.mifos.identity.api.v1.events.EventConstants;
import io.mifos.individuallending.api.v1.domain.product.ProductParameters;
import io.mifos.portfolio.api.v1.PermittableGroupIds;
import io.mifos.portfolio.api.v1.client.PortfolioManager;
import io.mifos.portfolio.api.v1.domain.*;
import io.mifos.provisioner.api.v1.client.Provisioner;
import io.mifos.provisioner.api.v1.domain.*;
import io.mifos.rhythm.api.v1.client.RhythmManager;
import io.mifos.rhythm.api.v1.events.BeatEvent;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.Base64Utils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.mifos.accounting.api.v1.EventConstants.POST_ACCOUNT;
import static io.mifos.accounting.api.v1.EventConstants.POST_LEDGER;
import static io.mifos.portfolio.api.v1.events.EventConstants.PUT_PRODUCT;
import static java.math.BigDecimal.ROUND_HALF_EVEN;

/**
 * @author Myrle Krantz
 */
@SuppressWarnings("SpringAutowiredFieldsWarningInspection")
@RunWith(SpringRunner.class)
@SpringBootTest()
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WorkflowTenantProvisioning {
  private static final String CLIENT_ID = "luckyLeprachaun";
  private static final String SCHEDULER_USER_NAME = "imhotep";
  private static final String TEST_LOGGER = "test-logger";
  private static final String LOAN_INCOME_LEDGER = "1100";
  private static final String ADMIN_USER_NAME = "antony";
  private static Microservice<Provisioner> provisionerService;
  private static Microservice<IdentityManager> identityService;
  private static Microservice<RhythmManager> rhythmService;
  private static Microservice<PortfolioManager> portfolioService;
  private static Microservice<LedgerManager> accountingService;
  private static DB EMBEDDED_MARIA_DB;

  @Configuration
  @ActiveMQForTest.EnableActiveMQListen
  @EnableApiFactory
  @ComponentScan("io.mifos.listener")
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }

    @Bean(name=TEST_LOGGER)
    public Logger logger() {
      return LoggerFactory.getLogger(TEST_LOGGER);
    }
  }

  @ClassRule
  public static final EurekaForTest eurekaForTest = new EurekaForTest();

  @ClassRule
  public static final ActiveMQForTest activeMQForTest = new ActiveMQForTest();

  @ClassRule
  public static final IntegrationTestEnvironment integrationTestEnvironment = new IntegrationTestEnvironment();

  @Autowired
  private ApiFactory apiFactory;
  @Autowired
  private
  EventRecorder eventRecorder;
  @Autowired
  @Qualifier(TEST_LOGGER)
  private
  Logger logger;

  @Autowired
  private DiscoveryClient discoveryClient;


  public WorkflowTenantProvisioning() {
    super();
  }

  @BeforeClass
  public static void setup() throws Exception {

    // start embedded Cassandra
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(TimeUnit.SECONDS.toMillis(30L));
    // start embedded MariaDB
    EMBEDDED_MARIA_DB = DB.newEmbeddedDB(3306);
    EMBEDDED_MARIA_DB.start();

    provisionerService = new Microservice<>(Provisioner.class, "provisioner", "0.1.0-BUILD-SNAPSHOT", integrationTestEnvironment);
    final TestEnvironment provisionerTestEnvironment = provisionerService.getProcessEnvironment();
    provisionerTestEnvironment.addSystemPrivateKeyToProperties();
    provisionerTestEnvironment.setProperty("system.initialclientid", CLIENT_ID);
    provisionerService.start();

    identityService = new Microservice<>(IdentityManager.class, "identity", "0.1.0-BUILD-SNAPSHOT", integrationTestEnvironment);
    identityService.start();

    rhythmService = new Microservice<>(RhythmManager.class, "rhythm", "0.1.0-BUILD-SNAPSHOT", integrationTestEnvironment)
            .addProperties(new ExtraProperties() {{
              setProperty("rhythm.beatCheckRate", Long.toString(TimeUnit.MINUTES.toMillis(10)));
              setProperty("rhythm.user", SCHEDULER_USER_NAME);}});
    rhythmService.start();

    accountingService = new Microservice<>(LedgerManager.class, "accounting", "0.1.0-BUILD-SNAPSHOT", integrationTestEnvironment);
    accountingService.start();

    portfolioService = new Microservice<>(PortfolioManager.class, "portfolio", "0.1.0-BUILD-SNAPSHOT", integrationTestEnvironment);
    final TestEnvironment portfolioTestEnvironment = portfolioService.getProcessEnvironment();
    portfolioTestEnvironment.setProperty("portfolio.bookInterestAsUser", SCHEDULER_USER_NAME);
    portfolioService.start();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    portfolioService.kill();
    accountingService.kill();
    rhythmService.kill();
    identityService.kill();
    provisionerService.kill();

    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    EMBEDDED_MARIA_DB.stop();
  }

  @Before
  public void before() throws InterruptedException {
    identityService.waitTillRegistered(discoveryClient);
    rhythmService.waitTillRegistered(discoveryClient);
    accountingService.waitTillRegistered(discoveryClient);
    portfolioService.waitTillRegistered(discoveryClient);

    provisionerService.setApiFactory(apiFactory);
    identityService.setApiFactory(apiFactory);
    rhythmService.setApiFactory(apiFactory);
    accountingService.setApiFactory(apiFactory);
    portfolioService.setApiFactory(apiFactory);
  }

  //@Test()
  public void z () {
    boolean run = true;

    while (run) {
      final Scanner scanner = new Scanner(System.in);
      final String nextLine = scanner.nextLine();
      if (nextLine != null && nextLine.equals("exit")) {
        run = false;
      }
    }
  }

  @Test
  public void test() throws InterruptedException, IOException {
    final String tenantAdminPassword = provisionAppsViaSeshat();


    final Authentication adminAuthentication = identityService.api().login(ADMIN_USER_NAME, tenantAdminPassword);

    final UserWithPassword officeAdministratorUser;
    final UserWithPassword loanOfficerUser;
    try (final AutoUserContext ignored = new AutoUserContext(ADMIN_USER_NAME, adminAuthentication.getAccessToken())) {
      checkCreationOfPermittableGroupsInIsis();

      final Role officeAdministratorRole = defineOfficeAdministratorRole();

      identityService.api().createRole(officeAdministratorRole);

      officeAdministratorUser = new UserWithPassword();
      officeAdministratorUser.setIdentifier("narmer");
      officeAdministratorUser.setPassword(encodePassword("3100BC"));
      officeAdministratorUser.setRole(officeAdministratorRole.getIdentifier());

      identityService.api().createUser(officeAdministratorUser);
      Assert.assertTrue(eventRecorder.wait(EventConstants.OPERATION_POST_USER, officeAdministratorUser.getIdentifier()));


      identityService.api().logout();
    }

    enableUser(officeAdministratorUser);

    final Authentication officeAdministratorAuthentication = identityService.api().login(officeAdministratorUser.getIdentifier(), officeAdministratorUser.getPassword());
    try (final AutoUserContext ignored = new AutoUserContext(officeAdministratorUser.getIdentifier(), officeAdministratorAuthentication.getAccessToken())) {
      final Role loanOfficerRole = defineLoanOfficerRole();
      identityService.api().createRole(loanOfficerRole);

      loanOfficerUser = new UserWithPassword();
      loanOfficerUser.setIdentifier("iryhor");
      loanOfficerUser.setPassword(encodePassword("3150BC"));
      loanOfficerUser.setRole(loanOfficerRole.getIdentifier());

      identityService.api().createUser(loanOfficerUser);

      final LedgerImporter ledgerImporter = new LedgerImporter(accountingService.api(), logger);
      final ClassPathResource ledgersResource = new ClassPathResource("ledgers.csv");
      final URL ledgersUri = ledgersResource.getURL();
      ledgerImporter.importCSV(ledgersUri);
      Assert.assertTrue(this.eventRecorder.wait(POST_LEDGER, LOAN_INCOME_LEDGER));

      final AccountImporter accountImporter = new AccountImporter(accountingService.api(), logger);
      final ClassPathResource accountsResource = new ClassPathResource("accounts.csv");
      final URL accountsUri = accountsResource.getURL();
      accountImporter.importCSV(accountsUri);

      identityService.api().logout();
    }

    enableUser(loanOfficerUser);

    final Authentication employeeAuthentication = identityService.api().login(loanOfficerUser.getIdentifier(), loanOfficerUser.getPassword());

    try (final AutoUserContext ignored = new AutoUserContext(loanOfficerUser.getIdentifier(), employeeAuthentication.getAccessToken())) {
      final Product product = defineProductWithoutAccountAssignments(
          portfolioService.getProcessEnvironment().generateUniqueIdentifer("agro"));

      portfolioService.api().createProduct(product);
      Assert.assertTrue(this.eventRecorder.wait(io.mifos.portfolio.api.v1.events.EventConstants.POST_PRODUCT, product.getIdentifier()));

      final Set<AccountAssignment> incompleteAccountAssignments = portfolioService.api().getIncompleteAccountAssignments(product.getIdentifier());

      final Product changedProduct = portfolioService.api().getProduct(product.getIdentifier());

      final Ledger ledger = accountingService.api().findLedger(LOAN_INCOME_LEDGER);

      final Set<AccountAssignment> accountAssignments = incompleteAccountAssignments.stream()
              .map(x -> new AccountAssignment(x.getDesignator(), createAccount(ledger).getIdentifier()))
              .collect(Collectors.toSet());
      for (final AccountAssignment accountAssignment : accountAssignments) {
        Assert.assertTrue(this.eventRecorder.wait(POST_ACCOUNT, accountAssignment.getAccountIdentifier()));
      }
      changedProduct.setAccountAssignments(accountAssignments);

      portfolioService.api().changeProduct(changedProduct.getIdentifier(), changedProduct);
      Assert.assertTrue(this.eventRecorder.wait(PUT_PRODUCT, changedProduct.getIdentifier()));

      identityService.api().logout();
    }
  }

  private String provisionAppsViaSeshat() throws InterruptedException {
    final AuthenticationResponse authenticationResponse
            = provisionerService.api().authenticate(CLIENT_ID, ApiConstants.SYSTEM_SU, "oS/0IiAME/2unkN1momDrhAdNKOhGykYFH/mJN20");

    try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
      final Tenant tenant = defineTenant();

      provisionerService.api().createTenant(tenant);

      final Application isisApp = new Application();
      isisApp.setName(identityService.name());
      isisApp.setHomepage(identityService.uri());
      isisApp.setDescription("identity manager");
      isisApp.setVendor("Kuelap");

      provisionerService.api().createApplication(isisApp);

      final AssignedApplication isisAssigned = new AssignedApplication();
      isisAssigned.setName(identityService.name());

      //Test that repeated calls to provision identity manager don't break things.
      provisionerService.api().assignIdentityManager(tenant.getIdentifier(), isisAssigned);
      provisionerService.api().assignIdentityManager(tenant.getIdentifier(), isisAssigned);
      provisionerService.api().assignIdentityManager(tenant.getIdentifier(), isisAssigned);
      Assert.assertTrue(eventRecorder.wait(io.mifos.identity.api.v1.events.EventConstants.OPERATION_PUT_USER_PASSWORD, "antony"));
      Assert.assertTrue(eventRecorder.wait(io.mifos.identity.api.v1.events.EventConstants.OPERATION_PUT_USER_PASSWORD, "antony"));

      final IdentityManagerInitialization tenantAdminPassword
          = provisionerService.api().assignIdentityManager(tenant.getIdentifier(), isisAssigned);
      Assert.assertTrue(eventRecorder.wait(io.mifos.identity.api.v1.events.EventConstants.OPERATION_PUT_USER_PASSWORD, "antony"));


      //Creation of the schedulerUserRole, and permitting it to create application permission requests are needed in the
      //provisioning of portfolio.  Portfolio asks rhythm for a callback.  Rhythm asks identity for permission to send
      //that call back.  Rhythm needs permission to ask identity directly rather than through the provisioner because
      //the request is made outside of rhythm's initialization.
      final UserWithPassword schedulerUser = createSchedulerUserRoleAndPassword(tenantAdminPassword.getAdminPassword());

      provisionApp(tenant, rhythmService, "rhythm manager", io.mifos.rhythm.api.v1.events.EventConstants.INITIALIZE);

      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_APPLICATION_PERMISSION, new ApplicationPermissionEvent(rhythmService.name(), io.mifos.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT)));

      final Authentication schedulerUserAuthentication;
      try (final AutoGuest ignored2 = new AutoGuest()) {
        enableUser(schedulerUser);
        schedulerUserAuthentication = identityService.api().login(schedulerUser.getIdentifier(), schedulerUser.getPassword());
      }

      try (final AutoUserContext ignored2 = new AutoUserContext(schedulerUser.getIdentifier(), schedulerUserAuthentication.getAccessToken())) {
        identityService.api().setApplicationPermissionEnabledForUser(
                rhythmService.name(),
                io.mifos.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT,
                schedulerUser.getIdentifier(),
                true);
        Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_APPLICATION_PERMISSION_USER_ENABLED, new ApplicationPermissionUserEvent(rhythmService.name(), io.mifos.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT, schedulerUser.getIdentifier())));
      }

      provisionApp(tenant, accountingService, "ledger manager", io.mifos.accounting.api.v1.EventConstants.INITIALIZE);

      provisionApp(tenant, portfolioService, "portfolio manager", io.mifos.portfolio.api.v1.events.EventConstants.INITIALIZE);

      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP,
              io.mifos.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioService.name())));

      for (int i = 0; i < 24; i++) {
        Assert.assertTrue("Beat #" + i,
                eventRecorder.wait(io.mifos.rhythm.api.v1.events.EventConstants.POST_BEAT,
                        new BeatEvent(portfolioService.name(), "alignment" + i)));
      }

      final Authentication schedulerAuthentication;
      try (final AutoGuest ignored2 = new AutoGuest()) {
        schedulerAuthentication = identityService.api().login(schedulerUser.getIdentifier(), schedulerUser.getPassword());
      }

      try (final AutoUserContext ignored2 = new AutoUserContext(schedulerUser.getIdentifier(), schedulerAuthentication.getAccessToken())) {
        //Allow rhythm to send a beat to portfolio as the scheduler user.
        identityService.api().setApplicationPermissionEnabledForUser(
                rhythmService.name(),
                io.mifos.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioService.name()),
                schedulerUser.getIdentifier(),
                true);
        Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_APPLICATION_PERMISSION_USER_ENABLED, new ApplicationPermissionUserEvent(rhythmService.name(), io.mifos.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioService.name()), schedulerUser.getIdentifier())));
      }

      return tenantAdminPassword.getAdminPassword();
    }
  }

  private void checkCreationOfPermittableGroupsInIsis() throws InterruptedException {
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP, io.mifos.portfolio.api.v1.PermittableGroupIds.CASE_MANAGEMENT));
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP, io.mifos.portfolio.api.v1.PermittableGroupIds.PRODUCT_MANAGEMENT));
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP, io.mifos.portfolio.api.v1.PermittableGroupIds.PRODUCT_OPERATIONS_MANAGEMENT));
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP, io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_ACCOUNT));
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP, io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_JOURNAL));
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP, io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_LEDGER));

    identityService.api().getPermittableGroup(io.mifos.identity.api.v1.PermittableGroupIds.ROLE_MANAGEMENT);
    identityService.api().getPermittableGroup(io.mifos.identity.api.v1.PermittableGroupIds.IDENTITY_MANAGEMENT);
    identityService.api().getPermittableGroup(io.mifos.identity.api.v1.PermittableGroupIds.SELF_MANAGEMENT);
    identityService.api().getPermittableGroup(io.mifos.portfolio.api.v1.PermittableGroupIds.CASE_MANAGEMENT);
    identityService.api().getPermittableGroup(io.mifos.portfolio.api.v1.PermittableGroupIds.PRODUCT_MANAGEMENT);
    identityService.api().getPermittableGroup(io.mifos.portfolio.api.v1.PermittableGroupIds.PRODUCT_OPERATIONS_MANAGEMENT);
    identityService.api().getPermittableGroup(io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_ACCOUNT);
    identityService.api().getPermittableGroup(io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_JOURNAL);
    identityService.api().getPermittableGroup(io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_LEDGER);

  }

  private UserWithPassword createSchedulerUserRoleAndPassword(String tenantAdminPassword) throws InterruptedException {
    final Authentication adminAuthentication;
    try (final AutoGuest ignored = new AutoGuest()) {
      adminAuthentication = identityService.api().login(ADMIN_USER_NAME, tenantAdminPassword);
    }

    final UserWithPassword schedulerUser;
    try (final AutoUserContext ignored = new AutoUserContext(ADMIN_USER_NAME, adminAuthentication.getAccessToken())) {
      final Role schedulerRole = defineSchedulerRole();
      identityService.api().createRole(schedulerRole);

      schedulerUser = new UserWithPassword();
      schedulerUser.setIdentifier(SCHEDULER_USER_NAME);
      schedulerUser.setPassword(encodePassword("26500BC"));
      schedulerUser.setRole(schedulerRole.getIdentifier());

      identityService.api().createUser(schedulerUser);
      Assert.assertTrue(eventRecorder.wait(EventConstants.OPERATION_POST_USER, schedulerUser.getIdentifier()));
    }

    try (final AutoGuest ignored = new AutoGuest()) {
      enableUser(schedulerUser);
    }

    return schedulerUser;
  }

  private <T> void provisionApp(
          final Tenant tenant,
          final Microservice<T> service,
          final String description,
          final String initialize_event) throws InterruptedException {

    final Application app = new Application();
    app.setName(service.name());
    app.setHomepage(service.uri());
    app.setDescription(description);
    app.setVendor("Kuelap");

    provisionerService.api().createApplication(app);

    final AssignedApplication assignedApp = new AssignedApplication();
    assignedApp.setName(service.name());

    provisionerService.api().assignApplications(tenant.getIdentifier(), Collections.singletonList(assignedApp));

    Assert.assertTrue(this.eventRecorder.wait(initialize_event, initialize_event));
    Assert.assertTrue(this.eventRecorder.waitForMatch(EventConstants.OPERATION_PUT_APPLICATION_SIGNATURE,
            (ApplicationSignatureEvent x) -> x.getApplicationIdentifier().equals(service.name())));
  }

  private void enableUser(final UserWithPassword userWithPassword) throws InterruptedException {
    final Authentication passwordOnlyAuthentication
            = identityService.api().login(userWithPassword.getIdentifier(), userWithPassword.getPassword());
    try (final AutoUserContext ignored
                 = new AutoUserContext(userWithPassword.getIdentifier(), passwordOnlyAuthentication.getAccessToken()))
    {
      identityService.api().changeUserPassword(
              userWithPassword.getIdentifier(), new Password(userWithPassword.getPassword()));
      Assert.assertTrue(eventRecorder.wait(EventConstants.OPERATION_PUT_USER_PASSWORD,
              userWithPassword.getIdentifier()));
    }
  }

  private static String encodePassword(final String password) {
    return Base64Utils.encodeToString(password.getBytes());
  }

  private Tenant defineTenant() {
    final Tenant tenant = new Tenant();
    tenant.setName("dudette");
    tenant.setIdentifier(TenantContextHolder.checkedGetIdentifier());
    tenant.setDescription("oogie boogie woman");

    final CassandraConnectionInfo cassandraConnectionInfo = new CassandraConnectionInfo();
    cassandraConnectionInfo.setClusterName("Test Cluster");
    cassandraConnectionInfo.setContactPoints("127.0.0.1:9142");
    cassandraConnectionInfo.setKeyspace("comp_test");
    cassandraConnectionInfo.setReplicas("3");
    cassandraConnectionInfo.setReplicationType("Simple");
    tenant.setCassandraConnectionInfo(cassandraConnectionInfo);

    final DatabaseConnectionInfo databaseConnectionInfo = new DatabaseConnectionInfo();
    databaseConnectionInfo.setDriverClass("org.mariadb.jdbc.Driver");
    databaseConnectionInfo.setDatabaseName("comp_test");
    databaseConnectionInfo.setHost("localhost");
    databaseConnectionInfo.setPort("3306");
    databaseConnectionInfo.setUser("root");
    databaseConnectionInfo.setPassword("mysql");
    tenant.setDatabaseConnectionInfo(databaseConnectionInfo);
    return tenant;
  }

  private Role defineOfficeAdministratorRole() {
    final Permission roleManagementPermission = new Permission();
    roleManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    roleManagementPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.ROLE_MANAGEMENT);

    final Permission userManagementPermission = new Permission();
    userManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    userManagementPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.IDENTITY_MANAGEMENT);

    final Permission ledgerManagementPermission = new Permission();
    ledgerManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    ledgerManagementPermission.setPermittableEndpointGroupIdentifier(io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_LEDGER);

    final Permission accountManagementPermission = new Permission();
    accountManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    accountManagementPermission.setPermittableEndpointGroupIdentifier(io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_ACCOUNT);

    final Role role = new Role();
    role.setIdentifier("orgadmin");
    role.setPermissions(Arrays.asList(roleManagementPermission, userManagementPermission, ledgerManagementPermission, accountManagementPermission));

    return role;
  }

  private Role defineSchedulerRole() {
    final Permission permissionRequestionCreationPermission = new Permission();
    permissionRequestionCreationPermission.setAllowedOperations(Collections.singleton(AllowedOperation.CHANGE));
    permissionRequestionCreationPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT);

    final Permission beatPublishToPortfolioPermission = new Permission();
    beatPublishToPortfolioPermission.setAllowedOperations(Collections.singleton(AllowedOperation.CHANGE));
    beatPublishToPortfolioPermission.setPermittableEndpointGroupIdentifier(io.mifos.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioService.name()));

    final Role role = new Role();
    role.setIdentifier("scheduler");
    role.setPermissions(Arrays.asList(permissionRequestionCreationPermission, beatPublishToPortfolioPermission));

    return role;
  }

  private Role defineLoanOfficerRole() {
    final Permission ledgerReadPermission = new Permission();
    ledgerReadPermission.setAllowedOperations(Collections.singleton(AllowedOperation.READ));
    ledgerReadPermission.setPermittableEndpointGroupIdentifier(io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_LEDGER);

    final Permission accountReadCreatePermission = new Permission();
    accountReadCreatePermission.setAllowedOperations(Stream.of(AllowedOperation.READ, AllowedOperation.CHANGE).collect(Collectors.toSet()));
    accountReadCreatePermission.setPermittableEndpointGroupIdentifier(io.mifos.accounting.api.v1.PermittableGroupIds.THOTH_ACCOUNT);

    final Permission productOperationsManagementPermission = new Permission();
    productOperationsManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    productOperationsManagementPermission.setPermittableEndpointGroupIdentifier(PermittableGroupIds.PRODUCT_OPERATIONS_MANAGEMENT);

    final Permission productManagementPermission = new Permission();
    productManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    productManagementPermission.setPermittableEndpointGroupIdentifier(PermittableGroupIds.PRODUCT_MANAGEMENT);

    final Permission caseManagementPermission = new Permission();
    caseManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    caseManagementPermission.setPermittableEndpointGroupIdentifier(PermittableGroupIds.CASE_MANAGEMENT);

    final Role role = new Role();
    role.setIdentifier("loanOfficer");
    role.setPermissions(Arrays.asList(
            ledgerReadPermission,
            accountReadCreatePermission,
            productOperationsManagementPermission,
            productManagementPermission,
            caseManagementPermission));
    return role;
  }

  static private Product defineProductWithoutAccountAssignments(final String identifier) {
    final Product product = new Product();
    product.setIdentifier(identifier);
    product.setPatternPackage("io.mifos.individuallending.api.v1");

    product.setName("Agricultural Loan");
    product.setDescription("Loan for seeds or agricultural equipment");
    product.setTermRange(new TermRange(ChronoUnit.MONTHS, 12));
    product.setBalanceRange(new BalanceRange(fixScale(BigDecimal.ZERO), fixScale(new BigDecimal(10000))));
    product.setInterestRange(new InterestRange(BigDecimal.valueOf(3, 2), BigDecimal.valueOf(12, 2)));
    product.setInterestBasis(InterestBasis.CURRENT_BALANCE);

    product.setCurrencyCode("XXX");
    product.setMinorCurrencyUnitDigits(2);

    product.setAccountAssignments(Collections.emptySet());

    final ProductParameters productParameters = new ProductParameters();

    productParameters.setMoratoriums(Collections.emptyList());
    productParameters.setMaximumDispersalCount(5);

    final Gson gson = new Gson();
    product.setParameters(gson.toJson(productParameters));
    return product;
  }

  static private BigDecimal fixScale(final BigDecimal bigDecimal)
  {
    return bigDecimal.setScale(4, ROUND_HALF_EVEN);
  }

  private Account createAccount(final Ledger ledger) {
    final Account account = defineAccount(ledger.getIdentifier(), accountingService.getProcessEnvironment().generateUniqueIdentifer(ledger.getIdentifier() + ".", 3));

    accountingService.api().createAccount(account);
    return account;
  }

  private Account defineAccount(final String ledgerIdentifier, final String identifier)
  {
    final Account account = new Account();
    account.setIdentifier(identifier);
    account.setName(identifier);
    account.setLedger(ledgerIdentifier);
    account.setHolders(Collections.singleton("humptyDumpty"));
    account.setState(Account.State.OPEN.name());
    account.setType(AccountType.REVENUE.name());
    account.setBalance(0d);
    return account;
  }
}