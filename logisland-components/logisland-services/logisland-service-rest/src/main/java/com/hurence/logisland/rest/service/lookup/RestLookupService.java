/**
 * Copyright (C) 2016 Hurence (support@hurence.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hurence.logisland.rest.service.lookup;

import com.burgstaller.okhttp.AuthenticationCacheInterceptor;
import com.burgstaller.okhttp.CachingAuthenticatorDecorator;
import com.burgstaller.okhttp.digest.CachingAuthenticator;
import com.burgstaller.okhttp.digest.DigestAuthenticator;
import com.hurence.logisland.annotation.behavior.DynamicProperties;
import com.hurence.logisland.annotation.behavior.DynamicProperty;
import com.hurence.logisland.annotation.documentation.CapabilityDescription;
import com.hurence.logisland.annotation.documentation.Tags;
import com.hurence.logisland.annotation.lifecycle.OnDisabled;
import com.hurence.logisland.annotation.lifecycle.OnEnabled;
import com.hurence.logisland.classloading.PluginProxy;
import com.hurence.logisland.component.AllowableValue;
import com.hurence.logisland.component.InitializationException;
import com.hurence.logisland.component.PropertyDescriptor;
import com.hurence.logisland.controller.AbstractControllerService;
import com.hurence.logisland.controller.ControllerServiceInitializationContext;
import com.hurence.logisland.record.Record;
import com.hurence.logisland.serializer.*;
import com.hurence.logisland.service.lookup.LookupFailureException;
import com.hurence.logisland.service.lookup.RecordLookupService;
import com.hurence.logisland.service.proxy.ProxyConfiguration;
import com.hurence.logisland.service.proxy.ProxyConfigurationService;
import com.hurence.logisland.service.proxy.ProxySpec;
import com.hurence.logisland.util.string.StringUtils;
import com.hurence.logisland.validator.StandardValidators;
import com.hurence.logisland.validator.Validator;
import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.trimToEmpty;

/**
 *
 * @see <a href="https://github.com/apache/nifi/blob/master/nifi-nar-bundles/nifi-standard-services/nifi-lookup-services-bundle/nifi-lookup-services/src/main/java/org/apache/nifi/lookup/RestLookupService.java">
 *      Processor inspired from RestLookupService nifi processor
 *     </a>
 */
@Tags({ "rest", "lookup", "json", "xml", "http" })
@CapabilityDescription("Use a REST service to look up values.")
@DynamicProperties({
        @DynamicProperty(name = "*", value = "*", description = "All dynamic properties are added as HTTP headers with the name " +
                "as the header name and the value as the header value.")
})
public class RestLookupService extends AbstractControllerService implements RecordLookupService {
    static final PropertyDescriptor URL = new PropertyDescriptor.Builder()
            .name("rest.lookup.url")
            .displayName("URL")
            .description("The URL for the REST endpoint. Expression language is evaluated against the lookup key/value pairs")
            .expressionLanguageSupported(false)
            .required(true)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    public static final AllowableValue AVRO_SERIALIZER =
            new AllowableValue(AvroSerializer.class.getName(), "avro serialization", "serialize events as avro blocs");

    public static final AllowableValue JSON_SERIALIZER =
            new AllowableValue(JsonSerializer.class.getName(), "json serialization", "serialize events as json blocs");

    public static final AllowableValue EXTENDEND_JSON_SERIALIZER =
            new AllowableValue(ExtendedJsonSerializer.class.getName(), "extended json serialization", "serialize events as json blocs");

    public static final AllowableValue STRING_SERIALIZER =
            new AllowableValue(StringSerializer.class.getName(), "json serialization", "serialize events as json blocs");

    public static final AllowableValue KRYO_SERIALIZER =
            new AllowableValue(KryoSerializer.class.getName(), "kryo serialization", "serialize events as json blocs");

    public static final AllowableValue NO_SERIALIZER =
            new AllowableValue("none", "no serialization", "send events as bytes");

    public static final PropertyDescriptor RECORD_SERIALIZER = new PropertyDescriptor.Builder()
            .name("record.serializer")
            .description("the serializer needed for loading the payload and handling it as a record set.")
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .allowableValues(KRYO_SERIALIZER, JSON_SERIALIZER, STRING_SERIALIZER, AVRO_SERIALIZER, NO_SERIALIZER, EXTENDEND_JSON_SERIALIZER)
            .defaultValue(EXTENDEND_JSON_SERIALIZER.getValue())
            .build();

    static final PropertyDescriptor RECORD_SCHEMA = new PropertyDescriptor.Builder()
            .name("record.schema")
            .description("the schema definition for the deserializer (for response payload). You can limit data to retrieve this way")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

//    static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
//            .name("rest.lookup.ssl.context.service")
//            .displayName("SSL Context Service")
//            .description("The SSL Context Service used to provide client certificate information for TLS/SSL "
//                    + "connections.")
//            .required(false)
//            .identifiesControllerService(SSLContextService.class) //TODO
//            .build();

    public static final PropertyDescriptor PROP_BASIC_AUTH_USERNAME = new PropertyDescriptor.Builder()
            .name("rest.lookup.basic.auth.username")
            .displayName("Basic Authentication Username")
            .description("The username to be used by the client to authenticate against the Remote URL.  Cannot include control characters (0-31), ':', or DEL (127).")
            .required(false)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.createRegexMatchingValidator(Pattern.compile("^[\\x20-\\x39\\x3b-\\x7e\\x80-\\xff]+$")))
            .build();

    public static final PropertyDescriptor PROP_BASIC_AUTH_PASSWORD = new PropertyDescriptor.Builder()
            .name("rest.lookup.basic.auth.password")
            .displayName("Basic Authentication Password")
            .description("The password to be used by the client to authenticate against the Remote URL.")
            .required(false)
            .sensitive(true)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.createRegexMatchingValidator(Pattern.compile("^[\\x20-\\x7e\\x80-\\xff]+$")))
            .build();
    public static final PropertyDescriptor PROP_DIGEST_AUTH = new PropertyDescriptor.Builder()
            .name("rest.lookup.digest.auth")
            .displayName("Use Digest Authentication")
            .description("Whether to communicate with the website using Digest Authentication. 'Basic Authentication Username' and 'Basic Authentication Password' are used "
                    + "for authentication.")
            .required(false)
            .defaultValue("false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    private static final ProxySpec[] PROXY_SPECS = {ProxySpec.HTTP_AUTH, ProxySpec.SOCKS};
    public static final PropertyDescriptor PROXY_CONFIGURATION_SERVICE
            = ProxyConfiguration.createProxyConfigPropertyDescriptor(true, PROXY_SPECS);

    static final String MIME_TYPE_KEY = "mime.type";
    static final String BODY_KEY = "request.body";
    static final String METHOD_KEY = "request.method";

    static final List<PropertyDescriptor> DESCRIPTORS;
    static final Set<String> KEYS;

    static final List VALID_VERBS = Arrays.asList("delete", "get", "post", "put");

    static {
        DESCRIPTORS = Collections.unmodifiableList(Arrays.asList(
                URL,
                RECORD_SERIALIZER,
                RECORD_SCHEMA,
//                SSL_CONTEXT_SERVICE,//TODO
                PROXY_CONFIGURATION_SERVICE,
                PROP_BASIC_AUTH_USERNAME,
                PROP_BASIC_AUTH_PASSWORD,
                PROP_DIGEST_AUTH
        ));
        KEYS = Collections.emptySet();
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return DESCRIPTORS;
    }

    private volatile ProxyConfigurationService proxyConfigurationService;
    private volatile RecordSerializer serializer;
    private volatile OkHttpClient client;
    private volatile Map<String, String> headers;
    private volatile String urlTemplate;
    private volatile String basicUser;
    private volatile String basicPass;
    private volatile boolean isDigest;
    private volatile Pattern urlCoordinatePattern = Pattern.compile("\\$\\{([^}]*)\\}");
    private volatile Matcher matcher;

    @Override
    @OnEnabled
    public void init(ControllerServiceInitializationContext context) throws InitializationException {
        super.init(context);
        try {
            if (context.getPropertyValue(RECORD_SCHEMA).isSet()) {
                serializer = SerializerProvider.getSerializer(
                        context.getPropertyValue(RECORD_SERIALIZER).asString(),
                        context.getPropertyValue(RECORD_SCHEMA).asString());
            } else {
                serializer = SerializerProvider.getSerializer(context.getPropertyValue(RECORD_SERIALIZER).asString(), null);
            }

            OkHttpClient.Builder builder = new OkHttpClient.Builder();

            setAuthenticator(builder, context);

            proxyConfigurationService = PluginProxy.rewrap(
                    context.getPropertyValue(PROXY_CONFIGURATION_SERVICE).asControllerService()
            );
            if (proxyConfigurationService != null) {
                setProxy(builder);
            }

            //TODO
//            final SSLContextService sslService = context.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
//            final SSLContext sslContext = sslService == null ? null : sslService.createSSLContext(SSLContextService.ClientAuth.WANT);
//            if (sslService != null) {
//                builder.sslSocketFactory(sslContext.getSocketFactory());
//            }

            client = builder.build();

            buildHeaders(context);

            urlTemplate = context.getProperty(URL);
            matcher = urlCoordinatePattern.matcher(urlTemplate);
        } catch (Exception e) {
            throw new InitializationException(e);
        }
    }

    @OnDisabled
    public void onDisable() {
        this.urlTemplate = null;
    }

    private void buildHeaders(ControllerServiceInitializationContext context) {
        headers = new HashMap<>();
        for (PropertyDescriptor descriptor : context.getProperties().keySet()) {
            if (descriptor.isDynamic()) {
                headers.put(
                        descriptor.getDisplayName(),
                        context.getPropertyValue(descriptor).asString()
                );
            }
        }
    }

    private void setProxy(OkHttpClient.Builder builder) {
        ProxyConfiguration config = proxyConfigurationService.getConfiguration();
        if (!config.getProxyType().equals(Proxy.Type.DIRECT)) {
            final Proxy proxy = config.createProxy();
            builder.proxy(proxy);

            if (config.hasCredential()){
                builder.proxyAuthenticator((route, response) -> {
                    final String credential= Credentials.basic(config.getProxyUserName(), config.getProxyUserPassword());
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
            }
        }
    }

    @Override
    public Optional<Record> lookup(Record coordinates) throws LookupFailureException {
        final String endpoint = evaluateEndPoint(coordinates);
        final String mimeType = coordinates.hasField(MIME_TYPE_KEY) ? coordinates.getField(MIME_TYPE_KEY).asString() : null;
        final String method   = coordinates.hasField(METHOD_KEY) ? coordinates.getField(METHOD_KEY).asString().trim().toLowerCase() : "get";
        final String body     = coordinates.hasField(BODY_KEY) ? coordinates.getField(BODY_KEY).asString() : null;

        validateVerb(method);

        //check if body is ok if any
        if (StringUtils.isBlank(body)) {
            if (method.equals("post") || method.equals("put")) {
                throw new LookupFailureException(
                        String.format("Used HTTP verb %s without specifying the %s key to provide a payload.", method, BODY_KEY)
                );
            }
        } else {
            if (StringUtils.isBlank(mimeType)) {
                throw new LookupFailureException(
                        String.format("Request body is specified without its %s.", MIME_TYPE_KEY)
                );
            }
        }

        Request request = buildRequest(mimeType, method, body, endpoint);
        try {
            Response response = executeRequest(request);

            if (getLogger().isDebugEnabled()) {
                getLogger().debug("Response code {} was returned for coordinate {}",
                        new Object[]{response.code(), coordinates});
            }

            final ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return Optional.empty();
            }

            InputStream is = responseBody.byteStream();
            Record record = handleResponse(is);

            return Optional.ofNullable(record);
        } catch (Exception e) {
            getLogger().error(String.format("Could not execute lookup at endpoint '%s'.", endpoint), e);
            throw new LookupFailureException(e);
        }
    }

    protected String evaluateEndPoint(Record coordinates) throws LookupFailureException {
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!coordinates.hasField(key)) {
                throw new LookupFailureException(
                        String.format("coordinates did not contain required '%s' field for evaluating url template '%s'.", key, urlTemplate)
                );
            }
            matcher.appendReplacement(sb, coordinates.getField(key).asString());
        }
        matcher.appendTail(sb);
        matcher.reset();//reset matcher
        return sb.toString();
    }

    protected void validateVerb(String method) throws LookupFailureException {
        if (!VALID_VERBS.contains(method)) {
            throw new LookupFailureException(String.format("%s is not a supported HTTP verb.", method));
        }
    }

    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .displayName(propertyDescriptorName)
                .addValidator(Validator.VALID)
                .dynamic(true)
                .expressionLanguageSupported(false)
                .build();
    }

    protected Response executeRequest(Request request) throws IOException {
        return client.newCall(request).execute();
    }

    private Record handleResponse(InputStream is) throws IOException {
        try {
            Record record = serializer.deserialize(is);
            return record;
        } catch (Exception ex) {
            is.close();
            throw ex;
        }
    }

    private Request buildRequest(final String mimeType, final String method, final String body, final String endpoint) {
        RequestBody requestBody = null;
        if (body != null) {
            final MediaType mt = MediaType.parse(mimeType);
            requestBody = RequestBody.create(mt, body);
        }
        Request.Builder request = new Request.Builder()
                .url(endpoint);
        switch(method) {
            case "delete":
                request = body != null ? request.delete(requestBody) : request.delete();
                break;
            case "get":
                /*
                ignoring body as mentioned here:
                https://tools.ietf.org/html/rfc2616#section-4.3
                https://tools.ietf.org/html/rfc2616#section-9.3
                */
                request = request.get();
                break;
            case "post":
                request = request.post(requestBody);
                break;
            case "put":
                request = request.put(requestBody);
                break;
        }
        //add headers defined by service
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request = request.addHeader(header.getKey(), header.getValue());
            }
        }
        //add auth defined by service
        if (!basicUser.isEmpty() && !isDigest) {
            String credential = Credentials.basic(basicUser, basicPass);
            request = request.header("Authorization", credential);
        }

        return request.build();
    }

    private void setAuthenticator(OkHttpClient.Builder okHttpClientBuilder, ControllerServiceInitializationContext context) {
        final String authUser = trimToEmpty(context.getProperty(PROP_BASIC_AUTH_USERNAME));
        this.basicUser = authUser;

        isDigest = context.getPropertyValue(PROP_DIGEST_AUTH).asBoolean();
        final String authPass = trimToEmpty(context.getProperty(PROP_BASIC_AUTH_PASSWORD));
        this.basicPass = authPass;
        // If the username/password properties are set then check if digest auth is being used
        if (!authUser.isEmpty() && isDigest) {

            /*
             * OkHttp doesn't have built-in Digest Auth Support. A ticket for adding it is here[1] but they authors decided instead to rely on a 3rd party lib.
             *
             * [1] https://github.com/square/okhttp/issues/205#issuecomment-154047052
             */
            final Map<String, CachingAuthenticator> authCache = new ConcurrentHashMap<>();
            com.burgstaller.okhttp.digest.Credentials credentials = new com.burgstaller.okhttp.digest.Credentials(authUser, authPass);
            final DigestAuthenticator digestAuthenticator = new DigestAuthenticator(credentials);

            okHttpClientBuilder.interceptors().add(new AuthenticationCacheInterceptor(authCache));
            okHttpClientBuilder.authenticator(new CachingAuthenticatorDecorator(digestAuthenticator, authCache));
        }
    }

    @Override
    public Class<?> getValueType() {
        return Record.class;
    }

    @Override
    public Set<String> getRequiredKeys() {
        return KEYS;
    }
}