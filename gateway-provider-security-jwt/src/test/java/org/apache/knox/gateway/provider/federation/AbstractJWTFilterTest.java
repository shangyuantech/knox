/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.knox.gateway.provider.federation;

import com.github.benmanes.caffeine.cache.Cache;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.codec.binary.Base64;
import org.apache.knox.gateway.provider.federation.jwt.filter.AbstractJWTFilter;
import org.apache.knox.gateway.provider.federation.jwt.filter.SSOCookieFederationFilter;
import org.apache.knox.gateway.security.PrimaryPrincipal;
import org.apache.knox.gateway.services.security.token.JWTokenAttributes;
import org.apache.knox.gateway.services.security.token.JWTokenAuthority;
import org.apache.knox.gateway.services.security.token.impl.JWT;
import org.apache.knox.gateway.services.security.token.impl.JWTToken;
import org.apache.knox.gateway.util.X509CertificateUtil;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.security.auth.Subject;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.MessageFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;

public abstract class AbstractJWTFilterTest  {
  protected static final String SERVICE_URL = "https://localhost:8888/resource";
  private static final String dnTemplate = "CN={0},OU=Test,O=Hadoop,L=Test,ST=Test,C=US";

  protected AbstractJWTFilter handler;
  protected static RSAPublicKey publicKey;
  protected static RSAPrivateKey privateKey;
  protected static String pem;

  protected abstract void setTokenOnRequest(HttpServletRequest request, SignedJWT jwt);
  protected abstract void setGarbledTokenOnRequest(HttpServletRequest request, SignedJWT jwt);
  protected abstract String getAudienceProperty();
  protected abstract String getVerificationPemProperty();

  private static String buildDistinguishedName(String hostname) {
    final String cn = Character.isAlphabetic(hostname.charAt(0)) ? hostname : "localhost";
    String[] paramArray = new String[1];
    paramArray[0] = cn;
    return new MessageFormat(dnTemplate, Locale.ROOT).format(paramArray);
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    KeyPair KPair = kpg.generateKeyPair();
    String dn = buildDistinguishedName(InetAddress.getLocalHost().getHostName());
    Certificate cert = X509CertificateUtil.generateCertificate(dn, KPair, 365, "SHA1withRSA");
    byte[] data = cert.getEncoded();
    Base64 encoder = new Base64( 76, "\n".getBytes( StandardCharsets.US_ASCII ) );
    pem = new String(encoder.encodeToString( data ).getBytes( StandardCharsets.US_ASCII ), StandardCharsets.US_ASCII).trim();

    publicKey = (RSAPublicKey) KPair.getPublic();
    privateKey = (RSAPrivateKey) KPair.getPrivate();
  }

  @After
  public void tearDown() {
    handler.destroy();
  }

  @Test
  public void testValidJWT() throws Exception {
    try {
      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
                             new Date(new Date().getTime() + 5000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testValidAudienceJWT() throws Exception {
    try {
      Properties props = getProperties();
      props.put(getAudienceProperty(), "bar");
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
                             new Date(new Date().getTime() + 5000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testInvalidAudienceJWT() throws Exception {
    try {
      Properties props = getProperties();
      props.put(getAudienceProperty(), "foo");
      props.put("sso.authentication.provider.url", "https://localhost:8443/gateway/knoxsso/api/v1/websso");

      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
                             new Date(new Date().getTime() + 5000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be true.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testValidAudienceJWTWhitespace() throws Exception {
    try {
      Properties props = getProperties();
      props.put(getAudienceProperty(), " foo, bar ");
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
                             new Date(new Date().getTime() + 5000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testNoTokenAudience() throws Exception {
    try {
      Properties props = getProperties();
      props.put(getAudienceProperty(), "bar");
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice", null,
                             new Date(new Date().getTime() + 5000), new Date(), privateKey, "RS256");

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be true.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testNoAudienceConfigured() throws Exception {
    try {
      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice", null,
                             new Date(new Date().getTime() + 5000), new Date(), privateKey, "RS256");

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testEmptyAudienceConfigured() throws Exception {
    try {
      Properties props = getProperties();
      props.put(getAudienceProperty(), "");
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice", null,
                             new Date(new Date().getTime() + 5000), new Date(), privateKey, "RS256");

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testValidVerificationPEM() throws Exception {
    try {
      Properties props = getProperties();

      props.put(getAudienceProperty(), "bar");
      props.put("sso.authentication.provider.url", "https://localhost:8443/gateway/knoxsso/api/v1/websso");
      props.put(getVerificationPemProperty(), pem);
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
                             new Date(new Date().getTime() + 50000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testExpiredJWT() throws Exception {
    try {
      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
          new Date(new Date().getTime() - 1000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be false.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testValidJWTNoExpiration() throws Exception {
    try {
      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice", null, privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL).anyTimes();
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testUnableToParseJWT() throws Exception {
    try {
      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "bob",
                             new Date(new Date().getTime() + 5000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setGarbledTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL).anyTimes();
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be true.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testFailedSignatureValidationJWT() throws Exception {
    try {
      // Create a private key to sign the token
      KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
      kpg.initialize(2048);

      KeyPair kp = kpg.genKeyPair();

      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      // Create a token with an expiration such that it's valid at test time, so the signature will be verified
      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER,
                             "bob",
                             new Date(new Date().getTime() + TimeUnit.MINUTES.toMillis(10)),
                             (RSAPrivateKey)kp.getPrivate());

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL).anyTimes();
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be true.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testInvalidVerificationPEM() throws Exception {
    try {
      Properties props = getProperties();

      KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
      kpg.initialize(2048);

      KeyPair KPair = kpg.generateKeyPair();
      String dn = buildDistinguishedName(InetAddress.getLocalHost().getHostName());
      Certificate cert = X509CertificateUtil.generateCertificate(dn, KPair, 365, "SHA1withRSA");
      byte[] data = cert.getEncoded();
      Base64 encoder = new Base64( 76, "\n".getBytes( StandardCharsets.US_ASCII ) );
      String failingPem = new String(encoder.encodeToString( data ).getBytes( StandardCharsets.US_ASCII ), StandardCharsets.US_ASCII).trim();

      props.put(getAudienceProperty(), "bar");
      props.put(getVerificationPemProperty(), failingPem);
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
                             new Date(new Date().getTime() + TimeUnit.MINUTES.toMillis(10)), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be true.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testInvalidIssuer() throws Exception {
    try {
      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT("new-issuer", "alice", new Date(new Date().getTime() + 5000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be true.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testValidIssuerViaConfig() throws Exception {
    try {
      Properties props = getProperties();
      props.setProperty(AbstractJWTFilter.JWT_EXPECTED_ISSUER, "new-issuer");
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT("new-issuer", "alice", new Date(new Date().getTime() + 5000), privateKey);

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled);
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testRS512SignatureAlgorithm() throws Exception {
    try {
      Properties props = getProperties();
      props.put(AbstractJWTFilter.JWT_EXPECTED_SIGALG, "RS512");
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER,
                             "alice",
                             new Date(new Date().getTime() + TimeUnit.MINUTES.toMillis(10)),
                             new Date(),
                             privateKey,
                             JWSAlgorithm.RS512.getName());

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
      Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
      Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
      Assert.assertEquals("Not the expected principal", "alice", ((Principal)principals.toArray()[0]).getName());
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testInvalidSignatureAlgorithm() throws Exception {
    try {
      Properties props = getProperties();
      props.put(AbstractJWTFilter.JWT_EXPECTED_SIGALG, AbstractJWTFilter.JWT_DEFAULT_SIGALG);
      handler.init(new TestFilterConfig(props));

      // Create a token with an expiration such that it's valid at test time, so the signature will be verified
      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER,
                             "alice",
                             new Date(new Date().getTime() + TimeUnit.MINUTES.toMillis(10)),
                             new Date(),
                             privateKey,
                             JWSAlgorithm.RS384.getName());

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be false.", chain.doFilterCalled );
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testNotBeforeJWT() throws Exception {
    try {
      Properties props = getProperties();
      handler.init(new TestFilterConfig(props));

      SignedJWT jwt = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER, "alice",
                             new Date(new Date().getTime() + 5000),
                             new Date(new Date().getTime() + 5000), privateKey,
                             JWSAlgorithm.RS256.getName());

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should not be false.", chain.doFilterCalled);
      Assert.assertNull("No Subject should be returned.", chain.subject);
    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testVerificationOptimization() throws Exception {
    try {

      final String principalAlice = "alice";
      final String principalBob   = "bob";

      final SignedJWT jwt_alice = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER,
                                         principalAlice,
                                         new Date(new Date().getTime() + TimeUnit.MINUTES.toMillis(10)),
                                         new Date(),
                                         privateKey,
                                         JWSAlgorithm.RS512.getName());

      final SignedJWT jwt_bob = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER,
                                       principalBob,
                                       new Date(new Date().getTime() + TimeUnit.MINUTES.toMillis(10)),
                                       new Date(),
                                       privateKey,
                                       JWSAlgorithm.RS512.getName());

      Properties props = getProperties();
      props.put(AbstractJWTFilter.JWT_EXPECTED_SIGALG, "RS512");
      props.put(AbstractJWTFilter.JWT_VERIFIED_CACHE_MAX, "1");
      handler.init(new TestFilterConfig(props));
      Assert.assertEquals("Expected no token verification calls yet.",
                          0, ((TokenVerificationCounter) handler).getVerificationCount());

      HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
      setTokenOnRequest(request, jwt_alice);

      EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
      EasyMock.expect(request.getQueryString()).andReturn(null);
      HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
      EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
      EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
      EasyMock.replay(request, response);

      doTestVerificationOptimization(request, response, principalAlice);
      Assert.assertEquals("Expected token verification to have been performed.",
                          1, ((TokenVerificationCounter) handler).getVerificationCount());

      // Do it again, and the verification should just use the cached result
      doRepeatTestVerificationOptimization(request, response, jwt_alice, principalAlice);
      Assert.assertEquals("Expected token verification to have been skipped the second time.",
                          1, ((TokenVerificationCounter) handler).getVerificationCount());

      // Do it again, with a different token
      doRepeatTestVerificationOptimization(request, response, jwt_bob, principalBob);
      Assert.assertEquals("Expected token verification to have been skipped the second time.",
                          2, ((TokenVerificationCounter) handler).getVerificationCount());


      // Wait for the first token verification record to be evicted
      evictVerifiedTokenRecords();

      // Do it again, and the verification should be performed again for the first token since it should have been
      // removed from the verified tokens cache (since the max size is 1)
      doRepeatTestVerificationOptimization(request, response, jwt_alice, principalAlice);
      Assert.assertEquals("Expected verification to have been performed for the token evicted from the verified cache.",
                          3, ((TokenVerificationCounter) handler).getVerificationCount());

    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  @Test
  public void testExpiredTokensEvictedFromSignatureVerificationCache() throws Exception {
    try {

      final String principalAlice = "alice";

      Properties props = getProperties();
      props.put(AbstractJWTFilter.JWT_EXPECTED_SIGALG, "RS512");
      props.put(AbstractJWTFilter.JWT_VERIFIED_CACHE_MAX, "1");
      handler.init(new TestFilterConfig(props));
      Assert.assertEquals("Expected no token verification calls yet.",
                          0, ((TokenVerificationCounter) handler).getVerificationCount());

      long expiration = new Date().getTime() + TimeUnit.SECONDS.toMillis(2);
      final SignedJWT jwt_alice = getJWT(AbstractJWTFilter.JWT_DEFAULT_ISSUER,
                                         principalAlice,
                                         new Date(expiration),
                                         new Date(),
                                         privateKey,
                                         JWSAlgorithm.RS512.getName());

      HttpServletRequest request = createMockRequest(jwt_alice);
      HttpServletResponse response = createMockResponse();
      EasyMock.replay(request, response);

      TestFilterChain chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled);
      Assert.assertEquals("The signature verification record for the token should have been added.",
                          1, getSignatureVerificationCacheSize());

      // Do it again, after the token has expired
      request = createMockRequest(jwt_alice);
      response = createMockResponse();
      EasyMock.replay(request, response);

      // Wait for the token to expire
      while (System.currentTimeMillis() < expiration) {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          //
        }
      }

      chain = new TestFilterChain();
      handler.doFilter(request, response, chain);
      Assert.assertFalse("doFilterCalled should be false since the token is expired.", chain.doFilterCalled);

      Assert.assertEquals("The signature verification record for the expired token should have been removed.",
                          0, getSignatureVerificationCacheSize());

    } catch (ServletException se) {
      fail("Should NOT have thrown a ServletException.");
    }
  }

  private HttpServletRequest createMockRequest(final SignedJWT jwt) {
    HttpServletRequest request = EasyMock.createNiceMock(HttpServletRequest.class);
    setTokenOnRequest(request, jwt);
    EasyMock.expect(request.getRequestURL()).andReturn(new StringBuffer(SERVICE_URL)).anyTimes();
    EasyMock.expect(request.getQueryString()).andReturn(null).anyTimes();
    return request;
  }

  private HttpServletResponse createMockResponse() throws Exception {
    HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
    EasyMock.expect(response.encodeRedirectURL(SERVICE_URL)).andReturn(SERVICE_URL);
    EasyMock.expect(response.getOutputStream()).andAnswer(DummyServletOutputStream::new).anyTimes();
    return response;
  }

  private void doRepeatTestVerificationOptimization(final HttpServletRequest request,
                                                    final HttpServletResponse response,
                                                    final SignedJWT jwt,
                                                    final String expectedPrincipal) throws Exception {
    EasyMock.reset(request, response);
    setTokenOnRequest(request, jwt);
    EasyMock.replay(request, response);

    doTestVerificationOptimization(request, response, expectedPrincipal);
  }

  private void doTestVerificationOptimization(final HttpServletRequest request,
                                              final HttpServletResponse response,
                                              final String expectedPrincipal) throws Exception {

    TestFilterChain chain = new TestFilterChain();
    handler.doFilter(request, response, chain);
    Assert.assertTrue("doFilterCalled should not be false.", chain.doFilterCalled );
    Set<PrimaryPrincipal> principals = chain.subject.getPrincipals(PrimaryPrincipal.class);
    Assert.assertFalse("No PrimaryPrincipal", principals.isEmpty());
    Assert.assertEquals("Not the expected principal", expectedPrincipal, ((Principal)principals.toArray()[0]).getName());
  }

  /**
   * Wait for the size limit enforcement, such that the Least-Recently-Used verified token record(s) will be evicted.
   */
  private void evictVerifiedTokenRecords() throws Exception {
    Field f = handler.getClass().getSuperclass().getSuperclass().getDeclaredField("verifiedTokens");
    f.setAccessible(true);
    Cache<String, Boolean> cache = (Cache<String, Boolean>) f.get(handler);
    cache.cleanUp();
  }

  private long getSignatureVerificationCacheSize() throws Exception {
    Field f = handler.getClass().getSuperclass().getSuperclass().getDeclaredField("verifiedTokens");
    f.setAccessible(true);
    Cache<String, Boolean> cache = (Cache<String, Boolean>) f.get(handler);
    return cache.estimatedSize();
  }

  protected Properties getProperties() {
    Properties props = new Properties();
    props.setProperty(
        SSOCookieFederationFilter.SSO_AUTHENTICATION_PROVIDER_URL,
        "https://localhost:8443/authserver");
    return props;
  }

  protected SignedJWT getJWT(String issuer, String sub, Date expires, RSAPrivateKey privateKey)
      throws Exception {
    return getJWT(issuer, sub, expires, new Date(), privateKey, JWSAlgorithm.RS256.getName());
  }

  protected SignedJWT getJWT(String issuer, String sub, Date expires, Date nbf, RSAPrivateKey privateKey,
                             String signatureAlgorithm)
      throws Exception {
    return getJWT(issuer, sub, "bar", expires, nbf, privateKey, signatureAlgorithm);
  }

  protected SignedJWT getJWT(String issuer, String sub, String aud, Date expires, Date nbf, RSAPrivateKey privateKey,
                             String signatureAlgorithm)
      throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
                                          .issuer(issuer)
                                          .subject(sub)
                                          .audience(aud)
                                          .expirationTime(expires)
                                          .notBeforeTime(nbf)
                                          .claim("scope", "openid")
                                          .claim(JWTToken.KNOX_ID_CLAIM, String.valueOf(UUID.randomUUID()))
                                          .build();

    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.parse(signatureAlgorithm)).build();

    SignedJWT signedJWT = new SignedJWT(header, claims);
    JWSSigner signer = new RSASSASigner(privateKey);

    signedJWT.sign(signer);

    return signedJWT;
  }

  protected static class TestFilterConfig implements FilterConfig {
    Properties props;

    public TestFilterConfig(Properties props) {
      this.props = props;
    }

    @Override
    public String getFilterName() {
      return null;
    }

    @Override
    public ServletContext getServletContext() {
//      JWTokenAuthority authority = EasyMock.createNiceMock(JWTokenAuthority.class);
//      GatewayServices services = EasyMock.createNiceMock(GatewayServices.class);
//      EasyMock.expect(services.getService("TokenService").andReturn(authority));
//      ServletContext context = EasyMock.createNiceMock(ServletContext.class);
//      EasyMock.expect(context.getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE).andReturn(new DefaultGatewayServices()));
      return null;
    }

    @Override
    public String getInitParameter(String name) {
      return props.getProperty(name, null);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
      return null;
    }

  }

  protected static class TestJWTokenAuthority implements JWTokenAuthority {

    private PublicKey verifyingKey;

    TestJWTokenAuthority(PublicKey verifyingKey) {
      this.verifyingKey = verifyingKey;
    }

    @Override
    public boolean verifyToken(JWT token) {
      JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) verifyingKey);
      return token.verify(verifier);
    }

    @Override
    public JWT issueToken(JWTokenAttributes jwtAttributes) {
      return null;
    }

    @Override
    public boolean verifyToken(JWT token, RSAPublicKey publicKey) {
      JWSVerifier verifier = new RSASSAVerifier(publicKey);
      return token.verify(verifier);
    }

    @Override
    public boolean verifyToken(JWT token, String jwksurl, String algorithm) {
     return false;
    }
  }

  protected static class TestFilterChain implements FilterChain {
    boolean doFilterCalled;
    Subject subject;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) {
      doFilterCalled = true;

      subject = Subject.getSubject( AccessController.getContext() );
    }
  }

  protected interface TokenVerificationCounter {
    int getVerificationCount();
  }

  static class DummyServletOutputStream extends ServletOutputStream {
      @Override
      public void write(int b) {
      }

      @Override
      public void setWriteListener(WriteListener arg0) {
      }

      @Override
      public boolean isReady() {
        return false;
      }
  }

}
