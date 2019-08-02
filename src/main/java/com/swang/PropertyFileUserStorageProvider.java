package com.swang;

import org.keycloak.common.util.EnvUtil;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.*;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.adapter.AbstractUserAdapter;
import org.keycloak.storage.adapter.AbstractUserAdapterFederatedStorage;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryProvider;
import org.keycloak.storage.user.UserRegistrationProvider;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class PropertyFileUserStorageProvider implements UserStorageProvider,
        UserLookupProvider,
        CredentialInputValidator,
        CredentialInputUpdater,
        UserRegistrationProvider,
        UserQueryProvider {

    public static final String UNSET_PASSWORD="#$!-UNSET-PASSWORD";

    protected KeycloakSession session;
    protected Properties properties;
    protected ComponentModel model;
    // map of loaded users in this transaction
    protected Map<String, UserModel> loadedUsers = new HashMap<>();

    public PropertyFileUserStorageProvider(KeycloakSession session, ComponentModel model, Properties properties) {
        this.session = session;
        this.model = model;
        this.properties = properties;
    }

    @Override
    public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        if (!(input instanceof UserCredentialModel)) return false;
        if (!input.getType().equals(CredentialModel.PASSWORD)) return false;
        UserCredentialModel cred = (UserCredentialModel)input;
        synchronized (properties) {
            properties.setProperty(user.getUsername(), cred.getValue());
            save();
        }
        return true;
    }

    @Override
    public void disableCredentialType(RealmModel realm, UserModel user, String credentialType) {
        if (!credentialType.equals(CredentialModel.PASSWORD)) return;
        synchronized (properties) {
            properties.setProperty(user.getUsername(), UNSET_PASSWORD);
            save();
        }

    }

    private static final Set<String> disableableTypes = new HashSet<>();

    static {
        disableableTypes.add(CredentialModel.PASSWORD);
    }

    @Override
    public Set<String> getDisableableCredentialTypes(RealmModel realm, UserModel user) {

        return disableableTypes;
    }

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return credentialType.equals(CredentialModel.PASSWORD);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        String password = properties.getProperty(user.getUsername());
        return credentialType.equals(CredentialModel.PASSWORD) && password != null;
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || !(input instanceof UserCredentialModel)) return false;

        UserCredentialModel cred = (UserCredentialModel)input;
        String password = properties.getProperty(user.getUsername());
        if (password == null || UNSET_PASSWORD.equals(password)) return false;
        return password.equals(cred.getValue());
    }

    @Override
    public void close() {

    }

    @Override
    public UserModel getUserById(String id, RealmModel realm) {
        StorageId storageId = new StorageId(id);
        String username = storageId.getExternalId();
        return getUserByUsername(username, realm);
    }

    @Override
    public UserModel getUserByUsername(String username, RealmModel realm) {
        UserModel adapter = loadedUsers.get(username);
        if (adapter == null) {
            String password = properties.getProperty(username);
            if (password != null) {
                adapter = createAdapter(realm, username);
                loadedUsers.put(username, adapter);
            }
        }
        return adapter;
    }

    @Override
    public UserModel getUserByEmail(String email, RealmModel realm) {
        return null;
    }

    protected UserModel createAdapter(RealmModel realm, final String username) {
        return new AbstractUserAdapterFederatedStorage(session, realm, model) {
            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public void setUsername(String username) {
                String pw = (String)properties.remove(username);
                if (pw != null) {
                    properties.put(username, pw);
                    save();
                }
            }
        };
    }

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        synchronized (properties) {
            properties.setProperty(username, UNSET_PASSWORD);
            save();
        }
        return createAdapter(realm, username);
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        synchronized (properties) {
            if (properties.remove(user.getUsername()) == null) return false;
            save();
            return true;
        }
    }

    public void save() {
        String path = model.getConfig().getFirst("path");
        path = EnvUtil.replace(path);
        try {
            FileOutputStream fos = new FileOutputStream(path);
            properties.store(fos, "");
            fos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getUsersCount(RealmModel realm) {
        return properties.size();
    }

    @Override
    public List<UserModel> getUsers(RealmModel realm) {
        return getUsers(realm, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<UserModel> getUsers(RealmModel realm, int firstResult, int maxResults) {
        List<UserModel> users = new LinkedList<>();
        int i = 0;
        for (Object obj : properties.keySet()) {
            if (i++ < firstResult) continue;
            String username = (String)obj;
            UserModel user = getUserByUsername(username, realm);
            users.add(user);
            if (users.size() >= maxResults) break;
        }
        return users;
    }

    @Override
    public List<UserModel> searchForUser(String search, RealmModel realm) {
        return searchForUser(search, realm, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<UserModel> searchForUser(String search, RealmModel realm, int firstResult, int maxResults) {
        List<UserModel> users = new LinkedList<>();
        int i = 0;
        for (Object obj : properties.keySet()) {
            String username = (String)obj;
            if (!username.contains(search)) continue;
            if (i++ < firstResult) continue;
            UserModel user = getUserByUsername(username, realm);
            users.add(user);
            if (users.size() >= maxResults) break;
        }
        return users;
    }

    @Override
    public List<UserModel> searchForUser(Map<String, String> params, RealmModel realm) {
        return searchForUser(params, realm, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<UserModel> searchForUser(Map<String, String> params, RealmModel realm, int firstResult, int maxResults) {
        // only support searching by username
        String usernameSearchString = params.get("username");
        if (usernameSearchString == null) return Collections.EMPTY_LIST;
        return searchForUser(usernameSearchString, realm, firstResult, maxResults);
    }

    @Override
    public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group, int firstResult, int maxResults) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> searchForUserByUserAttribute(String attrName, String attrValue, RealmModel realm) {
        return Collections.EMPTY_LIST;
    }
}
