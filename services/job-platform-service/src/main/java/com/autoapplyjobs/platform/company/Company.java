package com.autoapplyjobs.platform.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code company} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md} /
 * {@code docs/deduplication-design.md}.
 *
 * <p><strong>{@code normalized_name} and {@code domain} are deliberately
 * NOT unique</strong> - they are candidate employer-matching signals only,
 * never proof of identity (two different real companies can share a
 * normalized name; the same real company can be represented by more than
 * one {@code company} row before candidate matching/merging happens).
 * Accordingly, neither has a {@code @UniqueConstraint} here, and this
 * step's tests prove duplicates of both are accepted. The migration backs
 * this with plain (non-unique) indexes, {@code idx_company_normalized_name}
 * and {@code idx_company_domain}, for candidate lookups - see
 * {@code docs/deduplication-design.md} for how those lookups are meant to
 * be used (domain match is stronger evidence than a normalized-name match,
 * neither is ever an automatic merge). Employer canonicalization/merge
 * logic itself is not implemented here.
 *
 * <p>Canonical employer data for now: no setters, nothing here renames or
 * merges a company after creation.
 */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "normalized_name", nullable = false, updatable = false)
    private String normalizedName;

    @Column(name = "domain", updatable = false)
    private String domain;

    @Column(name = "website_url", updatable = false)
    private String websiteUrl;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Company() {
    }

    public Company(String name, String normalizedName, String domain, String websiteUrl) {
        this.name = name;
        this.normalizedName = normalizedName;
        this.domain = domain;
        this.websiteUrl = websiteUrl;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public String getDomain() {
        return domain;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Company other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Company{id=" + id + ", name=" + name + ", normalizedName=" + normalizedName + "}";
    }
}
