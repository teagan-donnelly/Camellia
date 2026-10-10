package com.camellia.repository;

import com.camellia.model.Organization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.camellia.repository.Jdbc.*;

@Repository
public class OrganizationRepository {

    private static final RowMapper<Organization> MAPPER = (rs, i) -> new Organization(
            rs.getString("ORG_ID"), rs.getString("NAME"), rs.getString("ORG_TYPE"), rs.getString("SOURCE_SYSTEM"),
            rs.getString("SOURCE_FORMAT"), rs.getString("CITY"), rs.getString("STATE"));

    private final JdbcTemplate jdbc;

    public OrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Organization> findAll() {
        return jdbc.query("SELECT * FROM HEALTHCARE_ORGANIZATIONS ORDER BY NAME", MAPPER);
    }

    public Set<String> existingIds() {
        return new HashSet<>(jdbc.queryForList("SELECT ORG_ID FROM HEALTHCARE_ORGANIZATIONS", String.class));
    }

    public void insertAll(List<Organization> orgs) {
        if (orgs.isEmpty()) return;
        batch(jdbc, """
                INSERT INTO HEALTHCARE_ORGANIZATIONS (ORG_ID, NAME, ORG_TYPE, SOURCE_SYSTEM, SOURCE_FORMAT, CITY, STATE, IS_SYNTHETIC)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)""",
                orgs.stream().map(o -> new Object[]{o.orgId(), o.name(), o.orgType(), o.sourceSystem(), o.sourceFormat(),
                        o.city(), o.state()}).toList(),
                S, S, S, S, S, S, S);
    }
}
