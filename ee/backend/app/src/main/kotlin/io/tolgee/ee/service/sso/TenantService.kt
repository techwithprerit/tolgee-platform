package io.tolgee.ee.service.sso

import io.tolgee.configuration.tolgee.SsoGlobalProperties
import io.tolgee.configuration.tolgee.SsoLocalProperties
import io.tolgee.constants.Message
import io.tolgee.ee.data.CreateProviderRequest
import io.tolgee.exceptions.NotFoundException
import io.tolgee.model.Organization
import io.tolgee.model.SsoTenant
import io.tolgee.repository.TenantRepository
import io.tolgee.security.thirdParty.SsoTenantConfig
import io.tolgee.security.thirdParty.SsoTenantConfig.Companion.toConfig
import io.tolgee.service.EeSsoTenantService
import org.springframework.stereotype.Service

@Service
class TenantService(
  private val tenantRepository: TenantRepository,
  private val ssoGlobalProperties: SsoGlobalProperties,
  private val ssoLocalProperties: SsoLocalProperties,
) : EeSsoTenantService{
  fun getById(id: Long): SsoTenant = tenantRepository.findById(id).orElseThrow { NotFoundException() }

  override fun getByDomain(domain: String): SsoTenant {
    return tenantRepository.findByDomain(domain) ?: throw NotFoundException()
  }

  fun getEnabledConfigByDomain(domain: String): SsoTenantConfig {
    return ssoGlobalProperties
      .takeIf { it.enabled && domain == it.domain }
      ?.toConfig()
      ?: domain
        .takeIf { ssoLocalProperties.enabled }
        ?.let { tenantRepository.findEnabledByDomain(it)?.toConfig() }
      ?: throw NotFoundException(Message.SSO_DOMAIN_NOT_FOUND_OR_DISABLED)
  }

  fun save(tenant: SsoTenant): SsoTenant = tenantRepository.save(tenant)

  fun findAll(): List<SsoTenant> = tenantRepository.findAll()

  fun findTenant(organizationId: Long): SsoTenant? = tenantRepository.findByOrganizationId(organizationId)

  fun getTenant(organizationId: Long): SsoTenant = findTenant(organizationId) ?: throw NotFoundException()

  fun saveOrUpdate(
    request: CreateProviderRequest,
    organization: Organization,
  ): SsoTenant {
    val tenant = findTenant(organization.id) ?: SsoTenant()
    return setAndSaveTenantsFields(tenant, request, organization)
  }

  private fun setAndSaveTenantsFields(
    tenant: SsoTenant,
    dto: CreateProviderRequest,
    organization: Organization,
  ): SsoTenant {
    tenant.name = dto.name ?: ""
    tenant.organization = organization
    tenant.domain = dto.domainName
    tenant.clientId = dto.clientId
    tenant.clientSecret = dto.clientSecret
    tenant.authorizationUri = dto.authorizationUri
    tenant.tokenUri = dto.tokenUri
    tenant.jwkSetUri = dto.jwkSetUri
    tenant.enabled = dto.isEnabled
    return save(tenant)
  }
}
