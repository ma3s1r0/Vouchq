package com.vouchq.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vouchq.notify.NotificationChannelEntity;
import com.vouchq.notify.NotificationChannelRepository;
import com.vouchq.notify.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CRUD + test for DB-backed notification channels (MA3-92, 기획서 §5.2 / §9.7).
 *
 * <p>RBAC: reads (GET) open to VIEWER+; mutations (POST/PUT/PATCH/DELETE under
 * {@code /api/settings/**}) require ADMIN — enforced centrally in
 * {@code com.vouchq.security.SecurityConfig}. Org context comes from
 * the authenticated user's org ({@link com.vouchq.tenancy.CurrentOrg}) (members/org provisioning is MA3-93, out of scope).
 */
@RestController
@RequestMapping("/api/settings/notification-channels")
public class SettingsNotificationChannelsController {

    private final NotificationChannelRepository channels;
    private final NotificationService notifications;
    private final ObjectMapper objectMapper;
    private final com.vouchq.tenancy.CurrentOrg currentOrg;

    public SettingsNotificationChannelsController(NotificationChannelRepository channels,
                                                  NotificationService notifications,
                                                  ObjectMapper objectMapper,
                                                  com.vouchq.tenancy.CurrentOrg currentOrg) {
        this.channels = channels;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.currentOrg = currentOrg;
    }

    @GetMapping
    public List<ApiDtos.NotificationChannelView> list() {
        UUID orgId = currentOrg.require();
        return channels.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .map(ApiDtos.NotificationChannelView::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.NotificationChannelView create(@RequestBody ApiDtos.NotificationChannelRequest req) {
        UUID orgId = currentOrg.require();
        NotificationChannelEntity.Type type = parseType(req.type());
        String name = require(req.name(), "name");
        String target = require(req.target(), "target");
        String config = normalizeJson(req.config());
        boolean enabled = req.enabled() != null && req.enabled();
        NotificationChannelEntity saved = channels.save(new NotificationChannelEntity(
                UUID.randomUUID(), orgId, type, name, target, config, enabled));
        return ApiDtos.NotificationChannelView.from(saved);
    }

    /** Full replace. */
    @PutMapping("/{id}")
    public ApiDtos.NotificationChannelView update(@PathVariable UUID id,
                                                  @RequestBody ApiDtos.NotificationChannelRequest req) {
        UUID orgId = currentOrg.require();
        NotificationChannelEntity c = load(id, orgId);
        c.setType(parseType(req.type()));
        c.setName(require(req.name(), "name"));
        c.setTarget(require(req.target(), "target"));
        c.setConfig(normalizeJson(req.config()));
        c.setEnabled(req.enabled() != null && req.enabled());
        return ApiDtos.NotificationChannelView.from(channels.save(c));
    }

    /** Partial update — null fields are left unchanged. */
    @PatchMapping("/{id}")
    public ApiDtos.NotificationChannelView patch(@PathVariable UUID id,
                                                 @RequestBody ApiDtos.NotificationChannelRequest req) {
        UUID orgId = currentOrg.require();
        NotificationChannelEntity c = load(id, orgId);
        if (req.type() != null) {
            c.setType(parseType(req.type()));
        }
        if (req.name() != null) {
            c.setName(require(req.name(), "name"));
        }
        if (req.target() != null) {
            c.setTarget(require(req.target(), "target"));
        }
        if (req.config() != null) {
            c.setConfig(normalizeJson(req.config()));
        }
        if (req.enabled() != null) {
            c.setEnabled(req.enabled());
        }
        return ApiDtos.NotificationChannelView.from(channels.save(c));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        UUID orgId = currentOrg.require();
        channels.delete(load(id, orgId));
    }

    /** Send a one-off test notification through the channel; reports success/failure. */
    @PostMapping("/{id}/test")
    public ApiDtos.ChannelTestResult test(@PathVariable UUID id) {
        UUID orgId = currentOrg.require();
        NotificationChannelEntity c = load(id, orgId);
        try {
            notifications.sendTest(c);
            return new ApiDtos.ChannelTestResult(c.getId(), true, "sent");
        } catch (RuntimeException e) {
            return new ApiDtos.ChannelTestResult(c.getId(), false, e.getMessage());
        }
    }

    private NotificationChannelEntity load(UUID id, UUID orgId) {
        return channels.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification channel: " + id));
    }

    private static NotificationChannelEntity.Type parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required (WEBHOOK | SLACK | EMAIL)");
        }
        try {
            return NotificationChannelEntity.Type.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown channel type: " + type + " (WEBHOOK | SLACK | EMAIL)");
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    /** Validate config is a JSON object (or default to {@code {}}); rejects malformed JSON. */
    private String normalizeJson(String config) {
        if (config == null || config.isBlank()) {
            return "{}";
        }
        try {
            var node = objectMapper.readTree(config);
            if (!node.isObject()) {
                throw new IllegalArgumentException("config must be a JSON object");
            }
            return node.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("config is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
