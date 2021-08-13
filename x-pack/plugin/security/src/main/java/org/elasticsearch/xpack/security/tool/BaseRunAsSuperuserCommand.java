/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.tool;

import joptsimple.OptionSet;
import joptsimple.OptionSpecBuilder;

import org.elasticsearch.Version;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.KeyStoreAwareCommand;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.settings.KeyStoreWrapper;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.elasticsearch.xpack.core.security.authc.RealmSettings;
import org.elasticsearch.xpack.core.security.authc.file.FileRealmSettings;
import org.elasticsearch.xpack.core.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.authc.file.FileUserPasswdStore;
import org.elasticsearch.xpack.security.authc.file.FileUserRolesStore;
import org.elasticsearch.xpack.security.support.FileAttributesChecker;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A {@link KeyStoreAwareCommand} that can be extended fpr any CLI tool that needs to allow a local user with
 * filesystem write access to perform actions on the node as a superuser. It leverages temporary file realm users
 * with a `superuser` role.
 */
public abstract class BaseRunAsSuperuserCommand extends KeyStoreAwareCommand {

    private static final String[] ROLES = new String[] { "superuser" };
    private static final int PASSWORD_LENGTH = 14;

    private final OptionSpecBuilder force;
    private final Function<Environment, CommandLineHttpClient> clientFunction;
    private final CheckedFunction<Environment, KeyStoreWrapper, Exception> keyStoreFunction;
    final SecureRandom secureRandom = new SecureRandom();

    public BaseRunAsSuperuserCommand(
        Function<Environment, CommandLineHttpClient> clientFunction,
        CheckedFunction<Environment, KeyStoreWrapper, Exception> keyStoreFunction,
        String description
    ) {
        super(description);
        this.clientFunction = clientFunction;
        this.keyStoreFunction = keyStoreFunction;
        force = parser.acceptsAll(List.of("f", "force"),
            "Use this option to force execution of the command against a cluster that is currently unhealthy.");
    }

    @Override
    protected final void execute(Terminal terminal, OptionSet options, Environment env) throws Exception {
        validate(terminal, options, env);
        ensureFileRealmEnabled(env.settings());
        KeyStoreWrapper keyStoreWrapper = keyStoreFunction.apply(env);
        final Environment newEnv;
        final Settings settings;
        if (keyStoreWrapper != null) {
            decryptKeyStore(keyStoreWrapper, terminal);
            Settings.Builder settingsBuilder = Settings.builder();
            settingsBuilder.put(env.settings(), true);
            if (settingsBuilder.getSecureSettings() == null) {
                settingsBuilder.setSecureSettings(keyStoreWrapper);
            }
            settings = settingsBuilder.build();
            newEnv = new Environment(settings, env.configFile());
        } else {
            newEnv = env;
            settings = env.settings();
        }

        final String username = generateUsername();
        try (SecureString password = new SecureString(generatePassword(PASSWORD_LENGTH))){
            final Hasher hasher = Hasher.resolve(XPackSettings.PASSWORD_HASHING_ALGORITHM.get(settings));
            final Path passwordFile = FileUserPasswdStore.resolveFile(newEnv);
            final Path rolesFile = FileUserRolesStore.resolveFile(newEnv);
            FileAttributesChecker attributesChecker = new FileAttributesChecker(passwordFile, rolesFile);
            // Store the roles file first so that when we get to store the user, it will definitely be a superuser
            Map<String, String[]> userRoles = FileUserRolesStore.parseFile(rolesFile, null);
            if (userRoles == null) {
                throw new IllegalStateException("File realm configuration file [" + rolesFile + "] is missing");
            }
            userRoles = new HashMap<>(userRoles);
            userRoles.put(username, ROLES);
            FileUserRolesStore.writeFile(userRoles, rolesFile);

            Map<String, char[]> users = FileUserPasswdStore.parseFile(passwordFile, null, settings);
            if (users == null) {
                throw new IllegalStateException("File realm configuration file [" + passwordFile + "] is missing");
            }
            users = new HashMap<>(users);
            users.put(username, hasher.hash(password));
            FileUserPasswdStore.writeFile(users, passwordFile);

            attributesChecker.check(terminal);
            final boolean forceExecution = options.has(force);
            checkClusterHealthWithRetries(newEnv, terminal, username, password, 5, forceExecution);
            executeCommand(terminal, options, newEnv, username, password);
        } catch (Exception e) {
            int exitCode;
            if (e instanceof UserException) {
                exitCode = ((UserException) e).exitCode;
            } else {
                exitCode = ExitCodes.DATA_ERROR;
            }
            throw new UserException(exitCode, e.getMessage());
        } finally {
            cleanup(terminal, newEnv, username);
        }
    }

    /**
     * Removes temporary file realm user from users and roles file
     */
    private void cleanup(Terminal terminal, Environment env, String username) throws Exception {
        final Path passwordFile = FileUserPasswdStore.resolveFile(env);
        final Path rolesFile = FileUserRolesStore.resolveFile(env);
        final List<String> errorMessages = new ArrayList<>();
        FileAttributesChecker attributesChecker = new FileAttributesChecker(passwordFile, rolesFile);

        Map<String, char[]> users = FileUserPasswdStore.parseFile(passwordFile, null, env.settings());
        if (users == null) {
            errorMessages.add("File realm configuration file [" + passwordFile + "] is missing");
        } else {
            users = new HashMap<>(users);
            char[] passwd = users.remove(username);
            if (passwd != null) {
                // No need to overwrite, if the user was already removed
                FileUserPasswdStore.writeFile(users, passwordFile);
                Arrays.fill(passwd, '\0');
            }
        }
        Map<String, String[]> userRoles = FileUserRolesStore.parseFile(rolesFile, null);
        if (userRoles == null) {
            errorMessages.add("File realm configuration file [" + rolesFile + "] is missing");
        } else {
            userRoles = new HashMap<>(userRoles);
            String[] roles = userRoles.remove(username);
            if (roles != null) {
                // No need to overwrite, if the user was already removed
                FileUserRolesStore.writeFile(userRoles, rolesFile);
            }
        }
        if ( errorMessages.isEmpty() == false ) {
            throw new UserException(ExitCodes.CONFIG, String.join(" , ", errorMessages));
        }
        attributesChecker.check(terminal);
    }

    private void ensureFileRealmEnabled(Settings settings) throws Exception {
        final Map<RealmConfig.RealmIdentifier, Settings> realms = RealmSettings.getRealmSettings(settings);
        Map<RealmConfig.RealmIdentifier, Settings> fileRealmSettings = realms.entrySet().stream()
            .filter(e -> e.getKey().getType().equals(FileRealmSettings.TYPE))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (fileRealmSettings.size() == 1) {
            final String fileRealmName = fileRealmSettings.entrySet().iterator().next().getKey().getName();
            if (RealmSettings.ENABLED_SETTING.apply(FileRealmSettings.TYPE)
                .getConcreteSettingForNamespace(fileRealmName)
                .get(settings) == false) throw new UserException(ExitCodes.CONFIG, "File realm must be enabled");
        }
        // Else it's either explicitly enabled, or not defined in the settings so it is implicitly enabled.
    }

    /**
     * Checks that we can connect to the cluster and that the cluster health is not RED. It optionally handles
     * retries as the file realm might not have reloaded the users file yet in order to authenticate our
     * newly created file realm user.
     */
    private void checkClusterHealthWithRetries(Environment env, Terminal terminal, String username, SecureString password, int retries,
                                               boolean force) throws Exception {
        CommandLineHttpClient client = clientFunction.apply(env);
        final URL clusterHealthUrl = CommandLineHttpClient.createURL(new URL(client.getDefaultURL()), "_cluster/health", "?pretty");
        final HttpResponse response;
        try {
            response = client.execute("GET", clusterHealthUrl, username, password, () -> null, CommandLineHttpClient::responseBuilder);
        } catch (Exception e) {
            throw new UserException(ExitCodes.UNAVAILABLE, "Failed to determine the health of the cluster. ", e);
        }
        final int responseStatus = response.getHttpStatus();
        if (responseStatus != HttpURLConnection.HTTP_OK) {
            // We try to write the roles file first and then the users one, but theoretically we could have loaded the users
            // before we have actually loaded the roles so we also retry on 403 ( temp user is found but has no roles )
            if ((responseStatus == HttpURLConnection.HTTP_UNAUTHORIZED || responseStatus == HttpURLConnection.HTTP_FORBIDDEN)
                && retries > 0 ) {
                terminal.println(
                    Terminal.Verbosity.VERBOSE,
                    "Unexpected http status [" + responseStatus + "] while attempting to determine cluster health. Will retry at most "
                        + retries
                        + " more times."
                );
                Thread.sleep(1000);
                retries -= 1;
                checkClusterHealthWithRetries(env, terminal, username, password, retries, force);
            } else {
                throw new UserException(
                    ExitCodes.DATA_ERROR,
                    "Failed to determine the health of the cluster. Unexpected http status [" + responseStatus + "]"
                );
            }
        } else {
            final String clusterStatus = Objects.toString(response.getResponseBody().get("status"), "");
            if (clusterStatus.isEmpty()) {
                throw new UserException(
                    ExitCodes.DATA_ERROR,
                    "Failed to determine the health of the cluster. Cluster health API did not return a status value."
                );
            } else if ("red".equalsIgnoreCase(clusterStatus) && force == false) {
                terminal.errorPrintln("Failed to determine the health of the cluster. Cluster health is currently RED.");
                terminal.errorPrintln("This means that some cluster data is unavailable and your cluster is not fully functional.");
                terminal.errorPrintln("The cluster logs (https://www.elastic.co/guide/en/elasticsearch/reference/"
                    + Version.CURRENT.major + "." + Version.CURRENT.minor + "/logging.html)"
                    + " might contain information/indications for the underlying cause");
                terminal.errorPrintln(
                    "It is recommended that you resolve the issues with your cluster before continuing");
                terminal.errorPrintln("It is very likely that the command will fail when run against an unhealthy cluster.");
                terminal.errorPrintln("");
                terminal.errorPrintln("If you still want to attempt to execute this command against an unhealthy cluster," +
                    " you can pass the `-f` parameter.");
                throw new UserException(ExitCodes.UNAVAILABLE,
                    "Failed to determine the health of the cluster. Cluster health is currently RED.");
            }
            // else it is yellow or green so we can continue
        }
    }

    protected char[] generatePassword(int passwordLength) {
        final char[] passwordChars = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789~!@#$%^&*-_=+?").toCharArray();
        char[] characters = new char[passwordLength];
        for (int i = 0; i < passwordLength; ++i) {
            characters[i] = passwordChars[secureRandom.nextInt(passwordChars.length)];
        }
        return characters;
    }

    private String generateUsername() {
        final char[] usernameChars = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789").toCharArray();
        int usernameLength = 8;
        char[] characters = new char[usernameLength];
        for (int i = 0; i < usernameLength; ++i) {
            characters[i] = usernameChars[secureRandom.nextInt(usernameChars.length)];
        }
        return "enrollment_autogenerated_" + new String(characters);
    }

    /**
     * This is called after we have created a temporary superuser in the file realm and verified that its
     * credentials work. The username and password of the generated user are passed as parameters. Overriding methods should
     * not try to close the password.
     */
    protected abstract void executeCommand(Terminal terminal, OptionSet options, Environment env, String username, SecureString password)
        throws Exception;

    /**
     * This method is called before we attempt to crete a temporary superuser in the file realm. Commands that
     * implement {@link BaseRunAsSuperuserCommand} can do preflight checks such as parsing and validating options without
     * the need to go through the process of attempting to create and remove the temporary user unnecessarily.
     */
    protected abstract void validate(Terminal terminal, OptionSet options, Environment env) throws Exception ;
}
