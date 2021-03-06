/**
 * Copyright (C) 2003, Intalio Inc.
 *
 * The program(s) herein may be used and/or copied only with the
 * written permission of Intalio Inc. or in accordance with the terms
 * and conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied.
 *
 * $Id: LDAPAuthenticationProvider.java,v 1.10 2005/02/24 18:14:12 boisvert Exp $
 */
package org.intalio.tempo.security.ldap;

import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.intalio.tempo.security.Property;
import org.intalio.tempo.security.authentication.AuthenticationAdmin;
import org.intalio.tempo.security.authentication.AuthenticationConstants;
import org.intalio.tempo.security.authentication.AuthenticationException;
import org.intalio.tempo.security.authentication.AuthenticationQuery;
import org.intalio.tempo.security.authentication.AuthenticationRuntime;
import org.intalio.tempo.security.authentication.UserNotFoundException;
import org.intalio.tempo.security.authentication.provider.AuthenticationProvider;
import org.intalio.tempo.security.rbac.RBACException;
import org.intalio.tempo.security.rbac.RBACQuery;
import org.intalio.tempo.security.rbac.provider.RBACProvider;
import org.intalio.tempo.security.util.IdentifierUtils;
import org.intalio.tempo.security.util.StringArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LDAPAuthenticationProvider
 *
 * @author <a href="http://www.intalio.com">&copy; Intalio Inc.</a>
 */
public class LDAPAuthenticationProvider
implements AuthenticationProvider, LDAPProperties {

    final static Logger LOG = LoggerFactory.getLogger("tempo.security");

    private final static Property[] EMPTY_PROPERTIES = new Property[0];

    private Map                     _env;

    private String                  _realm;

    private LDAPQueryEngine         _engine;

    private String                  _dn;

    private String                  _principleSyntax;

    private LDAPAuthentication      _queryRuntime;
    
    private Set<String> _workflowAdminUsers;
    
    private Set<String> _workflowAdminRoles;
    
  

	public void setWorkflowAdminUsers(Set<String> workflowAdminUsers) {
		_workflowAdminUsers = workflowAdminUsers;
	}


	public void setWorkflowAdminRoles(Set<String> workflowAdminRoles) {
		_workflowAdminRoles = workflowAdminRoles;
	}

	


    /**
     * Constructor
     */
    public LDAPAuthenticationProvider(String realm, LDAPQueryEngine engine, String dn, Map env) {
        super();
        _realm  = realm;
        _engine = engine;
        _dn     = dn;
        _env    = env;
    }

    /**
     */
    @SuppressWarnings("unchecked")
    public void initialize(Object config) throws AuthenticationException {
        if (config==null)
            throw new IllegalArgumentException("Configuration object is null!");
        if (config instanceof Map==false)
            throw new AuthenticationException("Unexpected configuration type, "+config.getClass().getName()+". Expect java.util.Map");
        _queryRuntime = new LDAPAuthentication((Map<String,String>)config);
    }

    /**
     */
    public String getName() throws AuthenticationException {
        return "LDAP Authentication Provider";
    }

    /**
     * @see org.intalio.tempo.security.authentication.provider.AuthenticationProvider#getAdmin()
     */
    public AuthenticationAdmin getAdmin() throws AuthenticationException {
        throw new RuntimeException("Method not implemented");
    }

    /**
     * @see org.intalio.tempo.security.authentication.provider.AuthenticationProvider#getQuery()
     */
    public AuthenticationQuery getQuery() throws AuthenticationException {
        return _queryRuntime;
    }

    /**
     * @see org.intalio.tempo.security.authentication.provider.AuthenticationProvider#getRuntime()
     */
    public AuthenticationRuntime getRuntime() throws AuthenticationException {
        return _queryRuntime;
    }

    /**
     */
    public void dispose() throws AuthenticationException {
        // nothing
    }

    private static Map<String,String> readProperties(String keyRoot, Map<String,String> source)
    throws IllegalArgumentException {
        Map<String,String> result = null;
        for (int i=0; true; i++) {
            String key   = keyRoot+'.'+i;
            String value = (String)source.get(key);
            if (value==null)
                break;
            int colon = value.indexOf(':');
            String front, back;
            if (colon==-1 ) {
                front = back = value;
            } else if ( colon==0 ) {
                front = value.substring(1);
                back = front;
            } else if ( colon==value.length()-1 ) {
                front = value.substring(0, colon);
                back = front;
            } else {
                front = value.substring(0, colon).trim();
                back  = value.substring(colon+1).trim();
            }
            if (front.length()==0 || back.length()==0)
                throw new IllegalArgumentException("Format is not reconized! key: "+key+" value: "+value);
            if (result==null)
                result = new TreeMap<String,String>();
            result.put(back, front);
        }
        return result;
    }

    private static String getNonNull(String key, Map map)
    throws IllegalArgumentException {
        Object res = map.get(key);
        if (res!=null)
            return res.toString();

        StringBuffer sb = new StringBuffer();
        sb.append(key);
        sb.append(" cannot be null!");
        throw new IllegalArgumentException(sb.toString());
    }

    class LDAPAuthentication implements AuthenticationQuery, AuthenticationRuntime {

        private String _userId;

        private Map<String,String> _userCredential;

        private Set<String> _multipleOu;

        LDAPAuthentication( Map<String,String> config ) throws IllegalArgumentException {
            try {
                _multipleOu = readProperties(SECURITY_LDAP_USER_BASE, config).keySet();
            } catch (Exception e) {
                _multipleOu = new HashSet<String>(1);
                _multipleOu.add(getNonNull(SECURITY_LDAP_USER_BASE, config));
            }
            _userId   = getNonNull(SECURITY_LDAP_USER_ID, config);
            _userCredential = readProperties(SECURITY_LDAP_USER_CREDENTIAL, config);
            if (_userCredential==null)
                throw new IllegalArgumentException("Property, "+SECURITY_LDAP_USER_CREDENTIAL+" is not set!");
            _principleSyntax = (String)config.get(SECURITY_LDAP_PRINCIPAL_SYNTAX);
            if (_principleSyntax==null || _principleSyntax.length()==0) {
                _principleSyntax = "dn";
            } else if (!_principleSyntax.equals("dn") && !_principleSyntax.equals("url")
                && !_principleSyntax.equals("user")) {
                throw new IllegalArgumentException("Property, "+SECURITY_LDAP_USER_CREDENTIAL+" does not allow value of "+_principleSyntax+". Only 'dn', 'url' and 'user' are supported.");
            }
        }

        /**
         * @see org.intalio.tempo.security.authentication.AuthenticationQuery#getUserCredentials(java.lang.String)
         */
        public Property[] getUserCredentials(String user)
        throws AuthenticationException, RemoteException {

            user = IdentifierUtils.stripRealm(user);
            try {
                short found = 0;

                Map<String,Property> result = new HashMap<String,Property>();
                Iterator<String> mmou = _multipleOu.iterator();
                while(mmou.hasNext()) {
                	found = _engine.queryProperties(user, mmou.next(), _userId, _userCredential, result);
                	if (found==LDAPQueryEngine.SUBJECT_NOT_FOUND) continue; else break;
                }
                
                if (LOG.isDebugEnabled())
                    LOG.debug("Result: "+found);
                if (found==LDAPQueryEngine.SUBJECT_NOT_FOUND)
                    throw new AuthenticationException("User, "+user+" is not found");
                if (found==LDAPQueryEngine.FIELD_NOT_FOUND)
                    return EMPTY_PROPERTIES;

                return (Property[])result.values().toArray(new Property[result.size()]);
            } catch (NamingException ne) {
                throw new AuthenticationException(ne);
            }
        }
        
        @Override
		public boolean isWorkflowAdmin(String identifier)
				throws AuthenticationException, RemoteException, RBACException {

        	String realm = IdentifierUtils.getRealm(identifier);
			LDAPSecurityProvider provider = _engine.getProvider();
			RBACProvider rbac = provider.getRBACProvider(realm);
			if (rbac == null) {
				throw new RBACException("SecurityProvider '"
						+ provider.getName()
						+ "' doesn't provide RBACProvider " + "for realm '"
						+ realm + "'");
			}
			
			RBACQuery query = rbac.getQuery();
			if (query == null) {
				throw new RBACException(
						"RBACProvider doesn't provide RBACQuery");
			}
			
			String[] roles = query.authorizedRoles(identifier);
			boolean isAdmin = false;
			for (int i = 0; i < roles.length && _workflowAdminRoles != null; i++) {
				isAdmin=_workflowAdminRoles.contains(roles[i]);
				if(isAdmin) break;
				
			}
			if (_workflowAdminUsers != null)
				return (isAdmin || _workflowAdminUsers.contains(identifier));
			else
				return isAdmin;
		}
        
        private boolean authenticate(String user, Property[] credentials, String userBase) throws UserNotFoundException, AuthenticationException, RemoteException {
        	DirContext ctx;
            user = IdentifierUtils.stripRealm(user);
            // OLD WAY OF HARDCODING THE USER PRINCIPAL
            String userPrincipal = _userId+"="+user+","+userBase; 
            try {
                // NOTE: new way,make a search on the user under the userbase
                userPrincipal = searchUser(user, userBase);   
            } catch (Exception e) {
                //throw new AuthenticationException(e);
            }
                
            
            
            try {
                if (LOG.isDebugEnabled())
                    LOG.debug("Authenticate("+user+") for realm, "+_realm);
                Property cred = null;
                for (int i = 0; i < credentials.length; i++) {
                    cred = credentials[i];
                    if (AuthenticationConstants.PROPERTY_PASSWORD.equals(cred.getName()) ) {
                        break;
                    } else if (AuthenticationConstants.PROPERTY_X509_CERTIFICATE.equals(cred.getName()) ) {
                        break;
                    } else {
                        cred = null;
                    }
                }
                if (cred==null) // can't find supported credentials
                    return false;

                // query doesn't work, as password is encrypted
                //return _engine.queryExist(user, (String)cred.getValue(), _userBase, _userId, cred.getName(), _userCredential);
                Properties env = new Properties();
                copyProperty(env, Context.SECURITY_AUTHENTICATION);
                copyProperty(env, Context.INITIAL_CONTEXT_FACTORY);
                copyProperty(env, Context.PROVIDER_URL);

                copyProperty(env, "javax.security.sasl.qop");
                copyProperty(env, "java.naming.security.sasl.realm");
                copyProperty(env, "javax.security.sasl.strength");

                if (_principleSyntax.equals("url")) {
                    env.put( Context.SECURITY_PRINCIPAL, user+"@"+toDot(_dn));
                } else if (_principleSyntax.equals("dn")) {
                    env.put( Context.SECURITY_PRINCIPAL, userPrincipal);
                } else if (_principleSyntax.equals("user")) {
                    env.put( Context.SECURITY_PRINCIPAL, user);
                } else {
                    throw new IllegalArgumentException("Property, "+SECURITY_LDAP_USER_CREDENTIAL+" does not allow value of '"+_principleSyntax+"'");
                }

                

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Authenticate env: "+env);
                }
                // Password should be put after doing debugging
                env.put( Context.SECURITY_CREDENTIALS, cred.getValue() );
                
                // workaround for the fact that Sun's JNDI provider does an
                // anonymous bind if no password is supplied for simple authentication
                // see http://java.sun.com/products/jndi/tutorial/ldap/faq/_context.html
                String auth = (String) env.get( Context.SECURITY_AUTHENTICATION );
                if ( auth.equalsIgnoreCase( "simple" ) ) {
                    String pw = (String) env.get( Context.SECURITY_CREDENTIALS );
                    if ( pw == null || pw.trim().length() == 0 ) {
                        return false;
                    }
                }

                // use the same way as obtaining _context to authenticate
                ctx = new InitialDirContext(env);

                String lookup = (userBase.equals("")) ? _dn : userBase+", "+_dn;
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Authenticate lookup: "+lookup);
                }
                ctx.lookup(lookup);
                ctx.close();
                return true;
            } catch (NamingException ne) {
                if (LOG.isDebugEnabled())
                    LOG.debug("Authentication of user, "+user+" failed!", ne);
                return false;
            }
        }

        public String searchUser(String user, String userBase) throws UserNotFoundException, NamingException, org.intalio.tempo.security.rbac.UserNotFoundException {
            //return _engine.searchUser(userBase, _userId, userBase);
            DirContext rootCtx = _engine.getProvider().getRootContext();
            String lookup = (userBase.equals("")) ? _dn : userBase+", "+_dn;
            SearchControls ctls = new SearchControls();
            ctls.setSearchScope(ctls.SUBTREE_SCOPE);
            String filter = "(&(objectClass=person) (&("+_userId+"="+user+")))";
            
            NamingEnumeration<SearchResult> answer = rootCtx.search(lookup, filter, ctls);
            while (answer.hasMore()) {
                SearchResult sr = (SearchResult) answer.next();
                if(LOG.isDebugEnabled()) {
                    LOG.debug("User search on"+_dn+" returned:" + sr.getNameInNamespace());
                }
                return sr.getNameInNamespace();
            }
            throw new UserNotFoundException(user);
        }

        /**
         * @see AuthenticationRuntime#authenticate(String, Property[])
         */
        public boolean authenticate(String user, Property[] credentials) throws UserNotFoundException, AuthenticationException, RemoteException {
            Iterator<String> iter = _multipleOu.iterator();
            while (iter.hasNext()) {
                if (authenticate(user, credentials, iter.next()))
                    return true;
            }
            return false;
        }


        /**
         * Convert an idenitifer from LDAP distingish name syntax to url-like
         * syntax. For example, from cn=admin,dc=intalio,dc=com to admin@intalio.com.
         *
         * @param dn
         * @return
         */
        private String toDot(String dn) {
            StringBuffer res = null;
            int index = 0;
            int comma = 0;
            int prev = 0;
            int len = dn.length();
            while (prev < len) {
                index = dn.indexOf("dc=", prev)+3;
                if (index==-1)
                    index = dn.indexOf("DC=", prev)+3;
                if (index==-1) {
                    if (res==null) {
                        return dn;
                    } else {
                        throw new IllegalArgumentException("The syntax is of dn invalid: "+dn);
                    }
                } else {
                    comma = dn.indexOf(',', index);
                    if (res==null)
                        res = new StringBuffer();
                    else
                        res.append('.');
                    if (comma==-1) {
                        res.append(dn.substring(index).trim());
                        break;
                    } else {
                        res.append(dn.substring(index, comma).trim());
                    }
                    prev = comma + 1;
                }
            }
            return res.toString();
        }

        private void copyProperty(Properties env, String propName) {
            String value = (String) _env.get(propName);
            if (value != null) {
                env.put(propName, value);
            }
        }

		

    }

	
}
