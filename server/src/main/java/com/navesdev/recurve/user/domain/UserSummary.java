package com.navesdev.recurve.user.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * What a listing shows of an operator (FR-01.4): the read model, indexed
 * in Elasticsearch and served from there (FR-06, FR-07). {@link User} is
 * the write model; this is a projection of it, rebuilt from it at any
 * time, and holds no rule.
 *
 * <p>The password hash has no field here, so it cannot reach the index
 * or a response by omission.
 *
 * <p>Mapping annotations only, the same discipline as the JPA annotations
 * on {@link User}. The index name takes the configurable prefix so that
 * integration tests never share an index with development. The index is
 * created by {@code UserIndexBootstrap}, never on demand, so that it
 * always carries the analyzers from {@code search/users-settings.json}.
 */
@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}users", createIndex = false)
@Setting(settingPath = "search/users-settings.json")
@Mapping(mappingPath = "search/users-mapping.json")
public record UserSummary(
        @Id UUID id,
        String name,
        String email,
        Set<Permission> permissions,
        boolean active,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt) {

    public static UserSummary of(User user) {
        return new UserSummary(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPermissions(),
                user.isActive(),
                user.getCreatedAt());
    }
}
