package io.tolgee.ee.service

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.JWSAlgorithmFamilyJWSKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor
import com.posthog.java.shaded.org.json.JSONObject
import io.tolgee.constants.Message
import io.tolgee.ee.exceptions.OAuthAuthorizationException
import io.tolgee.ee.model.SsoTenant
import io.tolgee.exceptions.AuthenticationException
import io.tolgee.model.UserAccount
import io.tolgee.model.enums.OrganizationRoleType
import io.tolgee.security.authentication.JwtService
import io.tolgee.security.payload.JwtAuthenticationResponse
import io.tolgee.service.organization.OrganizationRoleService
import io.tolgee.service.security.SignUpService
import io.tolgee.service.security.UserAccountService
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URL
import java.util.*

@Service
class OAuthService(
  private val jwtService: JwtService,
  private val userAccountService: UserAccountService,
  private val signUpService: SignUpService,
  private val restTemplate: RestTemplate,
  private val jwtProcessor: ConfigurableJWTProcessor<SecurityContext>,
  private val organizationRoleService: OrganizationRoleService,
  private val tenantService: TenantService,
) : Logging {
  fun handleOAuthCallback(
    registrationId: String,
    code: String,
    redirectUrl: String,
    error: String,
    errorDescription: String,
    invitationCode: String?,
  ): JwtAuthenticationResponse? {
    if (error.isNotBlank()) {
      logger.info("Third party auth failed: $errorDescription $error")
      throw OAuthAuthorizationException(
        Message.THIRD_PARTY_AUTH_FAILED,
        "$errorDescription $error",
      )
    }

    val tenant = tenantService.getByDomain(registrationId)

    val tokenResponse =
      exchangeCodeForToken(tenant, code, redirectUrl)
        ?: throw OAuthAuthorizationException(
          Message.TOKEN_EXCHANGE_FAILED,
          null,
        )

    val userInfo = verifyAndDecodeIdToken(tokenResponse.id_token, tenant.jwkSetUri)
    return register(userInfo, tenant, invitationCode)
  }

  fun exchangeCodeForToken(
    tenant: SsoTenant,
    code: String,
    redirectUrl: String,
  ): OAuth2TokenResponse? {
    val headers =
      HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
      }

    val body: MultiValueMap<String, String> = LinkedMultiValueMap()
    body.add("grant_type", "authorization_code")
    body.add("code", code)
    body.add("redirect_uri", redirectUrl)
    body.add("client_id", tenant.clientId)
    body.add("client_secret", tenant.clientSecret)
    body.add("scope", "openid")

    val request = HttpEntity(body, headers)
    return try {
      val response: ResponseEntity<OAuth2TokenResponse> =
        restTemplate.exchange(
          tenant.tokenUri,
          HttpMethod.POST,
          request,
          OAuth2TokenResponse::class.java,
        )
      response.body
    } catch (e: HttpClientErrorException) {
      logger.info("Failed to exchange code for token: ${e.message}")
      null // todo throw exception
    }
  }

  fun verifyAndDecodeIdToken(
    idToken: String,
    jwkSetUri: String,
  ): GenericUserResponse {
    try {
      val signedJWT = SignedJWT.parse(idToken)

      val jwkSource: JWKSource<SecurityContext> = RemoteJWKSet(URL(jwkSetUri))

      val keySelector = JWSAlgorithmFamilyJWSKeySelector(JWSAlgorithm.Family.RSA, jwkSource)
      jwtProcessor.jwsKeySelector = keySelector

      val jwtClaimsSet: JWTClaimsSet = jwtProcessor.process(signedJWT, null)

      val expirationTime: Date = jwtClaimsSet.expirationTime
      if (expirationTime.before(Date())) {
        throw OAuthAuthorizationException(Message.ID_TOKEN_EXPIRED, null)
      }

      return GenericUserResponse().apply {
        sub = jwtClaimsSet.subject
        name = jwtClaimsSet.getStringClaim("name")
        given_name = jwtClaimsSet.getStringClaim("given_name")
        family_name = jwtClaimsSet.getStringClaim("family_name")
        email = jwtClaimsSet.getStringClaim("email")
      }
    } catch (e: Exception) {
      logger.info(e.stackTraceToString())
      throw OAuthAuthorizationException(Message.USER_INFO_RETRIEVAL_FAILED, null)
    }
  }

  fun decodeJwt(jwt: String): JSONObject {
    val parts = jwt.split(".")
    if (parts.size != 3) throw IllegalArgumentException("JWT does not have 3 parts") // todo change exception type

    val payload = parts[1]
    val decodedPayload = String(Base64.getUrlDecoder().decode(payload))

    return JSONObject(decodedPayload)
  }

  private fun register(
    userResponse: GenericUserResponse,
    tenant: SsoTenant,
    invitationCode: String?,
  ): JwtAuthenticationResponse {
    val email =
      userResponse.email ?: let {
        logger.info("Third party user email is null. Missing scope email?")
        throw AuthenticationException(Message.THIRD_PARTY_AUTH_NO_EMAIL)
      }

    val userAccountOptional = userAccountService.findByThirdParty(tenant.domain, userResponse.sub!!)
    val user =
      userAccountOptional.orElseGet {
        userAccountService.findActive(email)?.let {
          throw AuthenticationException(Message.USERNAME_ALREADY_EXISTS)
        }

        val newUserAccount = UserAccount()
        newUserAccount.username =
          userResponse.email ?: throw AuthenticationException(Message.THIRD_PARTY_AUTH_NO_EMAIL)

        // build name for userAccount based on available fields by third party
        var name = userResponse.email!!.split("@")[0]
        if (userResponse.name != null) {
          name = userResponse.name!!
        } else if (userResponse.given_name != null && userResponse.family_name != null) {
          name = "${userResponse.given_name} ${userResponse.family_name}"
        }
        newUserAccount.name = name
        newUserAccount.thirdPartyAuthId = userResponse.sub
        newUserAccount.thirdPartyAuthType = tenant.domain
        newUserAccount.accountType = UserAccount.AccountType.THIRD_PARTY
        signUpService.signUp(newUserAccount, invitationCode, null)
        organizationRoleService.grantRoleToUser(
          newUserAccount,
          tenant.organizationId,
          OrganizationRoleType.MEMBER,
        )
        newUserAccount
      }
    val jwt = jwtService.emitToken(user.id)
    return JwtAuthenticationResponse(jwt)
  }

  class OAuth2TokenResponse(
    val id_token: String,
    val scope: String,
  )

  class GenericUserResponse {
    var sub: String? = null
    var name: String? = null

    @Suppress("ktlint:standard:property-naming")
    var given_name: String? = null

    @Suppress("ktlint:standard:property-naming")
    var family_name: String? = null
    var email: String? = null
  }
}
