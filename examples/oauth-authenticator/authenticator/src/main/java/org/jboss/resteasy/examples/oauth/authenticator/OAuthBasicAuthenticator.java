package org.jboss.resteasy.examples.oauth.authenticator;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.Principal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.oauth.OAuth;
import net.oauth.OAuthMessage;
import net.oauth.OAuthProblemException;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Realm;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.apache.catalina.authenticator.BasicAuthenticator;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityConstraint;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.catalina.realm.RealmBase;
import org.jboss.resteasy.auth.oauth.OAuthException;
import org.jboss.resteasy.auth.oauth.OAuthProvider;
import org.jboss.resteasy.auth.oauth.OAuthToken;
import org.jboss.resteasy.auth.oauth.OAuthUtils;
import org.jboss.resteasy.auth.oauth.OAuthValidator;


public class OAuthBasicAuthenticator extends AuthenticatorBase {

    private static final String INFO =
        "org.jboss.resteasy.examples.oauth.OAuthBasicAuthenticator/1.0";

    private static final Set<String> SUPPORTED_AUTH_METHODS = 
        new HashSet<String>(Arrays.asList("oauth", "basic", "oauth+basic", "basic+oauth"));
    
    private static final String DEFAULT_CONSUMER_ROLE = "user";
    
    private BasicAuthenticator ba = new BasicAuthenticator();

    protected String driver;
    protected String url;
    protected String user;
    protected String password;
    private String oauthProviderName;
    
    private Connection conn;
    private OAuthProvider oauthProvider;
    private OAuthValidator validator;
    
    private Realm originalRealm;
    
    public OAuthBasicAuthenticator() {
        super();
    }
    
    public String getInfo() {
        return INFO;
    }

    public void setDriver(String driver)
    {
        this.driver = driver;
    }

    public String getDriver() {
        return driver;
    }
    
    public void setUrl(String url)
    {
        this.url = url;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUser(String user)
    {
        this.user = user;
    }
    
    public String getUser()
    {
        return user;
    }
    
    public void setPassword(String password)
    {
        this.password = password;
    }
    
    public String getPassword()
    {
        return password;
    }
    
    public void setOauthProviderName(String oauthProviderName) {
        this.oauthProviderName = oauthProviderName;
    }

    public String getOauthProviderName() {
        return oauthProviderName;
    }

    @Override
    public void setContainer(Container container) {
        super.setContainer(container);
        ba.setContainer(container);
        originalRealm = container.getRealm();
    }
    
    @Override
    protected boolean authenticate(Request request, HttpServletResponse response, LoginConfig config)
            throws IOException {
        
        String authMethod = config.getAuthMethod();
        if (!SUPPORTED_AUTH_METHODS.contains(authMethod.toLowerCase())) {
            throw new SecurityException("Unsupported auth method : " + authMethod);    
        }
        
        String authorization = request.getHeader("Authorization");
        if (authorization != null) 
        {
            context.setRealm(originalRealm);
            return ba.authenticate(request, response, config);
        } 
        else 
        {
            try {
                doAuthenticateOAuth(request, response);
            } catch (ServletException ex) {
                throw new IOException(ex);
            }
        }
        return false;
        
    }
    
    
    @Override
    public void start() throws LifecycleException {
        super.start();
        
        try {
            Class.forName(driver);
            conn = DriverManager.getConnection(url, user, password);
            oauthProvider = (OAuthProvider)Class.forName(oauthProviderName).newInstance();
            validator = new OAuthValidator(oauthProvider);
        } catch (Exception ex) {
            throw new LifecycleException("In memory OAuth DB can not be created " + ex.getMessage());
        }
    }


    @Override
    public void stop() throws LifecycleException {
        super.stop();
        if (conn != null)
        {
            try {
                conn.close();
            } catch (Exception ex) {
                // ignore
            }
        }
    }
    
    
    protected void doAuthenticateOAuth(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        
        OAuthMessage message = OAuthUtils.readMessage(request);
        try{

            message.requireParameters(OAuth.OAUTH_CONSUMER_KEY,
                    OAuth.OAUTH_SIGNATURE_METHOD,
                    OAuth.OAUTH_SIGNATURE,
                    OAuth.OAUTH_TIMESTAMP,
                    OAuth.OAUTH_NONCE);

            String consumerKey = message.getParameter(OAuth.OAUTH_CONSUMER_KEY);
            org.jboss.resteasy.auth.oauth.OAuthConsumer consumer = oauthProvider.getConsumer(consumerKey);
        
            OAuthToken accessToken = null;
            String accessTokenString = message.getParameter(OAuth.OAUTH_TOKEN);
            
            if (accessTokenString != null) { 
                accessToken = oauthProvider.getAccessToken(consumer.getKey(), accessTokenString);
                OAuthUtils.validateRequestWithAccessToken(
                        request, message, accessToken, validator, consumer);
            } else {
                OAuthUtils.validateRequestWithoutAccessToken(
                        request, message, validator, consumer);
            }
            
            createPrincipalAndRoles(request, consumer, accessToken);
            getNext().invoke((Request)request, (Response)response);
            
        } catch (OAuthException x) {
            OAuthUtils.makeErrorResponse(response, x.getMessage(), x.getHttpCode(), oauthProvider);
        } catch (OAuthProblemException x) {
            OAuthUtils.makeErrorResponse(response, x.getProblem(), OAuthUtils.getHttpCode(x), oauthProvider);
        } catch (Exception x) {
            OAuthUtils.makeErrorResponse(response, x.getMessage(), HttpURLConnection.HTTP_INTERNAL_ERROR, oauthProvider);
        }
        
    }

    protected void createPrincipalAndRoles(HttpServletRequest request, 
            org.jboss.resteasy.auth.oauth.OAuthConsumer consumer,
            OAuthToken accessToken) 
    {
        
        List<String> roles = new ArrayList<String>();
        // get the default roles which may've been allocated to a consumer
        roles.add(DEFAULT_CONSUMER_ROLE);
        roles.addAll(convertPermissionsToRoles(accessToken.getPermissions()[0]));
        Realm realm = new OAuthRealm(consumer.getKey(), roles);
        context.setRealm(realm);
        
        final Principal principal = new GenericPrincipal(realm, consumer.getKey(), "", roles);
        ((Request)request).setUserPrincipal(principal);
        ((Request)request).setAuthType("OAuth");
    }
    
    private Set<String> convertPermissionsToRoles(String permissions) {
        Set<String> roles = new HashSet<String>();
        // get the default roles which may've been allocated to a consumer
        try {
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT role FROM permissions WHERE"
                    + " permission='" + permissions + "'");
            if (rs.next()) {
                String rolesValues = rs.getString("role");
                roles.add(rolesValues);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("No role exists for permission " + permissions);
        }
        return roles;
    }
    
    
    private static class OAuthRealm extends RealmBase {

        //private String username;
        private List<String> roles;
        
        public OAuthRealm(String username, List<String> roles) {
            //this.username = username;
            this.roles = roles;
        }
        
        @Override
        protected String getName() {
            return "OAuthRealm";
        }

        @Override
        protected String getPassword(String username) {
            return "";
        }

        @Override
        protected Principal getPrincipal(String username) {
            return new GenericPrincipal(this, username, "", null, null);
        }
        
        @Override
        public boolean hasResourcePermission(Request request, Response response,  
             SecurityConstraint[] constraints, Context context) {  
           return true;  
        } 
        
        @Override
        public boolean hasRole(Principal principal, String role) {  
           return roles.contains(role);  
        }
    }
}