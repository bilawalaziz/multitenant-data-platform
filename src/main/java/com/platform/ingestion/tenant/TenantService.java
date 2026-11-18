package com.platform.ingestion.tenant;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.FieldError;
import com.platform.ingestion.messaging.TopicProvisioner;
import com.platform.ingestion.tenant.TenantDtos.SettingsResponse;
import com.platform.ingestion.tenant.TenantDtos.TenantResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final Pattern SETTING_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final TenantRepository tenants;
    private final TenantSchemaManager schemaManager;
    private final TopicProvisioner topicProvisioner;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TenantService(
            TenantRepository tenants,
            TenantSchemaManager schemaManager,
            TopicProvisioner topicProvisioner,
            TransactionTemplate tx,
            Clock clock) {
        this.tenants = tenants;
        this.schemaManager = schemaManager;
        this.topicProvisioner = topicProvisioner;
        this.tx = tx;
        this.clock = clock;
    }

    /** Record + schema are created atomically; the Kafka topic is best effort (publisher ensures it later). */
    public TenantResponse register(String tenantCode, String displayName) {
        Tenant created = tx.execute(status -> {
            if (tenants.existsByTenantCode(tenantCode)) {
                throw new ApiException(ErrorCode.CONFLICT, "Tenant '" + tenantCode + "' already exists");
            }
            String schemaName = TenantSchemaManager.schemaNameFor(tenantCode);
            Tenant tenant = tenants.saveAndFlush(new Tenant(
                    UUID.randomUUID(),
                    tenantCode,
                    displayName,
                    TenantStatus.ACTIVE,
                    schemaName,
                    tenantCode + ".readings",
                    clock.instant()));
            schemaManager.createTenantSchema(schemaName);
            return tenant;
        });
        if (!topicProvisioner.tryEnsureTopic(created.getKafkaTopic())) {
            log.warn(
                    "Kafka topic {} could not be created at registration; the outbox publisher will ensure it later",
                    created.getKafkaTopic());
        }
        return TenantResponse.from(created);
    }

    public List<TenantResponse> list() {
        return tenants.findAllByOrderByTenantCodeAsc().stream()
                .map(TenantResponse::from)
                .toList();
    }

    public TenantResponse get(String tenantCode) {
        return TenantResponse.from(requireByCode(tenantCode));
    }

    public Tenant requireByCode(String tenantCode) {
        return tenants.findByTenantCode(tenantCode).orElseThrow(() -> ApiException.notFound("Tenant"));
    }

    public TenantContext requireContext(String tenantCode) {
        return requireByCode(tenantCode).toContext();
    }

    public SettingsResponse getSettings(String tenantCode) {
        Tenant tenant = requireByCode(tenantCode);
        return new SettingsResponse(
                tenantCode, tenant.getSchemaName(), schemaManager.readSettings(tenant.getSchemaName()));
    }

    public SettingsResponse updateSettings(String tenantCode, Map<String, String> settings) {
        List<FieldError> errors = settings.keySet().stream()
                .filter(k -> !SETTING_KEY.matcher(k).matches())
                .map(k -> new FieldError("settings." + k, "Setting key must match ^[a-z][a-z0-9_]{0,63}$"))
                .toList();
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Invalid setting key", errors);
        }
        Tenant tenant = requireByCode(tenantCode);
        tx.executeWithoutResult(s -> schemaManager.upsertSettings(tenant.getSchemaName(), settings));
        return getSettings(tenantCode);
    }
}
