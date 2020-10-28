/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.storage.implementation.sql;

import com.google.common.collect.ImmutableMap;
import com.google.gson.reflect.TypeToken;

import me.lucko.luckperms.common.actionlog.Log;
import me.lucko.luckperms.common.actionlog.LoggedAction;
import me.lucko.luckperms.common.bulkupdate.BulkUpdate;
import me.lucko.luckperms.common.bulkupdate.BulkUpdateStatistics;
import me.lucko.luckperms.common.bulkupdate.PreparedStatementBuilder;
import me.lucko.luckperms.common.context.ContextSetJsonSerializer;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.Track;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.GroupManager;
import me.lucko.luckperms.common.node.matcher.ConstraintNodeMatcher;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.storage.implementation.StorageImplementation;
import me.lucko.luckperms.common.storage.implementation.sql.connection.ConnectionFactory;
import me.lucko.luckperms.common.storage.misc.NodeEntry;
import me.lucko.luckperms.common.storage.misc.PlayerSaveResultImpl;
import me.lucko.luckperms.common.util.Uuids;
import me.lucko.luckperms.common.util.gson.GsonProvider;

import net.kyori.adventure.text.Component;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.model.PlayerSaveResult;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.node.Node;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SqlStorage implements StorageImplementation {
    private static final Type LIST_STRING_TYPE = new TypeToken<List<String>>(){}.getType();

    private static final String USER_PERMISSIONS_SELECT = "SELECT id, permission, value, server, world, expiry, contexts FROM '{prefix}user_permissions' WHERE uuid=?";
    private static final String USER_PERMISSIONS_DELETE_SPECIFIC = "DELETE FROM '{prefix}user_permissions' WHERE id=?";
    private static final String USER_PERMISSIONS_DELETE = "DELETE FROM '{prefix}user_permissions' WHERE uuid=?";
    private static final String USER_PERMISSIONS_INSERT = "INSERT INTO '{prefix}user_permissions' (uuid, permission, value, server, world, expiry, contexts) VALUES(?, ?, ?, ?, ?, ?, ?)";
    private static final String USER_PERMISSIONS_SELECT_DISTINCT = "SELECT DISTINCT uuid FROM '{prefix}user_permissions'";
    private static final String USER_PERMISSIONS_SELECT_PERMISSION = "SELECT uuid, id, permission, value, server, world, expiry, contexts FROM '{prefix}user_permissions' WHERE ";

    private static final String PLAYER_SELECT_UUID_BY_USERNAME = "SELECT uuid FROM '{prefix}players' WHERE username=? LIMIT 1";
    private static final String PLAYER_SELECT_USERNAME_BY_UUID = "SELECT username FROM '{prefix}players' WHERE uuid=? LIMIT 1";
    private static final String PLAYER_UPDATE_USERNAME_FOR_UUID = "UPDATE '{prefix}players' SET username=? WHERE uuid=?";
    private static final String PLAYER_INSERT = "INSERT INTO '{prefix}players' (uuid, username, primary_group) VALUES(?, ?, ?)";
    private static final String PLAYER_DELETE = "DELETE FROM '{prefix}players' WHERE uuid=?";
    private static final String PLAYER_SELECT_ALL_UUIDS_BY_USERNAME = "SELECT uuid FROM '{prefix}players' WHERE username=? AND NOT uuid=?";
    private static final String PLAYER_DELETE_ALL_UUIDS_BY_USERNAME = "DELETE FROM '{prefix}players' WHERE username=? AND NOT uuid=?";
    private static final String PLAYER_SELECT_BY_UUID = "SELECT username, primary_group FROM '{prefix}players' WHERE uuid=?";
    private static final String PLAYER_SELECT_PRIMARY_GROUP_BY_UUID = "SELECT primary_group FROM '{prefix}players' WHERE uuid=? LIMIT 1";
    private static final String PLAYER_UPDATE_PRIMARY_GROUP_BY_UUID = "UPDATE '{prefix}players' SET primary_group=? WHERE uuid=?";

    private static final String GROUP_PERMISSIONS_SELECT = "SELECT id, permission, value, server, world, expiry, contexts FROM '{prefix}group_permissions' WHERE name=?";
    private static final String GROUP_PERMISSIONS_SELECT_ALL = "SELECT name, id, permission, value, server, world, expiry, contexts FROM '{prefix}group_permissions'";
    private static final String GROUP_PERMISSIONS_DELETE_SPECIFIC = "DELETE FROM '{prefix}group_permissions' WHERE id=?";
    private static final String GROUP_PERMISSIONS_DELETE = "DELETE FROM '{prefix}group_permissions' WHERE name=?";
    private static final String GROUP_PERMISSIONS_INSERT = "INSERT INTO '{prefix}group_permissions' (name, permission, value, server, world, expiry, contexts) VALUES(?, ?, ?, ?, ?, ?, ?)";
    private static final String GROUP_PERMISSIONS_SELECT_PERMISSION = "SELECT name, id, permission, value, server, world, expiry, contexts FROM '{prefix}group_permissions' WHERE ";

    private static final String GROUP_SELECT_ALL = "SELECT name FROM '{prefix}groups'";
    private static final Map<String, String> GROUP_INSERT = ImmutableMap.of(
            "H2", "MERGE INTO '{prefix}groups' (name) VALUES(?)",
            "SQLite", "INSERT OR IGNORE INTO '{prefix}groups' (name) VALUES(?)",
            "PostgreSQL", "INSERT INTO '{prefix}groups' (name) VALUES(?) ON CONFLICT (name) DO NOTHING"
    );
    private static final String GROUP_INSERT_DEFAULT = "INSERT INTO '{prefix}groups' (name) VALUES(?) ON DUPLICATE KEY UPDATE name=name";
    private static final String GROUP_DELETE = "DELETE FROM '{prefix}groups' WHERE name=?";

    private static final String TRACK_INSERT = "INSERT INTO '{prefix}tracks' (name, 'groups') VALUES(?, ?)";
    private static final String TRACK_SELECT = "SELECT 'groups' FROM '{prefix}tracks' WHERE name=?";
    private static final String TRACK_SELECT_ALL = "SELECT * FROM '{prefix}tracks'";
    private static final String TRACK_UPDATE = "UPDATE '{prefix}tracks' SET 'groups'=? WHERE name=?";
    private static final String TRACK_DELETE = "DELETE FROM '{prefix}tracks' WHERE name=?";

    private static final String ACTION_INSERT = "INSERT INTO '{prefix}actions' (time, actor_uuid, actor_name, type, acted_uuid, acted_name, action) VALUES(?, ?, ?, ?, ?, ?, ?)";
    private static final String ACTION_SELECT_ALL = "SELECT * FROM '{prefix}actions'";

    private final LuckPermsPlugin plugin;
    
    private final ConnectionFactory connectionFactory;
    private final Function<String, String> statementProcessor;

    public SqlStorage(LuckPermsPlugin plugin, ConnectionFactory connectionFactory, String tablePrefix) {
        this.plugin = plugin;
        this.connectionFactory = connectionFactory;
        this.statementProcessor = connectionFactory.getStatementProcessor().compose(s -> s.replace("{prefix}", tablePrefix));
    }

    @Override
    public LuckPermsPlugin getPlugin() {
        return this.plugin;
    }

    @Override
    public String getImplementationName() {
        return this.connectionFactory.getImplementationName();
    }

    public ConnectionFactory getConnectionFactory() {
        return this.connectionFactory;
    }

    public Function<String, String> getStatementProcessor() {
        return this.statementProcessor;
    }

    @Override
    public void init() throws Exception {
        this.connectionFactory.init(this.plugin);

        boolean tableExists;
        try (Connection c = this.connectionFactory.getConnection()) {
            tableExists = tableExists(c, this.statementProcessor.apply("{prefix}user_permissions"));
        }

        if (!tableExists) {
            applySchema();
        }
    }

    private void applySchema() throws IOException, SQLException {
        List<String> statements;

        String schemaFileName = "me/lucko/luckperms/schema/" + this.connectionFactory.getImplementationName().toLowerCase() + ".sql";
        try (InputStream is = this.plugin.getBootstrap().getResourceStream(schemaFileName)) {
            if (is == null) {
                throw new IOException("Couldn't locate schema file for " + this.connectionFactory.getImplementationName());
            }

            statements = SchemaReader.getStatements(is).stream()
                    .map(this.statementProcessor)
                    .collect(Collectors.toList());
        }

        try (Connection connection = this.connectionFactory.getConnection()) {
            boolean utf8mb4Unsupported = false;

            try (Statement s = connection.createStatement()) {
                for (String query : statements) {
                    s.addBatch(query);
                }

                try {
                    s.executeBatch();
                } catch (BatchUpdateException e) {
                    if (e.getMessage().contains("Unknown character set")) {
                        utf8mb4Unsupported = true;
                    } else {
                        throw e;
                    }
                }
            }

            // try again
            if (utf8mb4Unsupported) {
                try (Statement s = connection.createStatement()) {
                    for (String query : statements) {
                        s.addBatch(query.replace("utf8mb4", "utf8"));
                    }

                    s.executeBatch();
                }
            }
        }
    }

    @Override
    public void shutdown() {
        try {
            this.connectionFactory.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public Map<Component, Component> getMeta() {
        return this.connectionFactory.getMeta();
    }

    @Override
    public void logAction(Action entry) throws SQLException {
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(ACTION_INSERT))) {
                writeAction(entry, ps);
                ps.execute();
            }
        }
    }

    @Override
    public Log getLog() throws SQLException {
        final Log.Builder log = Log.builder();
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(ACTION_SELECT_ALL))) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        log.add(readAction(rs));
                    }
                }
            }
        }
        return log.build();
    }

    @Override
    public void applyBulkUpdate(BulkUpdate bulkUpdate) throws SQLException {
        BulkUpdateStatistics stats = bulkUpdate.getStatistics();

        try (Connection c = this.connectionFactory.getConnection()) {
            if (bulkUpdate.getDataType().isIncludingUsers()) {
                String table = this.statementProcessor.apply("{prefix}user_permissions");
                try (PreparedStatement ps = bulkUpdate.buildAsSql().build(c, q -> q.replace("{table}", table))) {

                    if (bulkUpdate.isTrackingStatistics()) {
                        PreparedStatementBuilder builder = new PreparedStatementBuilder();
                        builder.append(USER_PERMISSIONS_SELECT_DISTINCT);
                        if (!bulkUpdate.getQueries().isEmpty()) {
                            builder.append(" WHERE ");
                            bulkUpdate.getQueries().forEach(query -> query.appendSql(builder));
                        }

                        try (PreparedStatement lookup = builder.build(c, this.statementProcessor)) {
                            try (ResultSet rs = lookup.executeQuery()) {
                                Set<UUID> uuids = new HashSet<>();

                                while (rs.next()) {
                                    uuids.add(Uuids.fromString(rs.getString("uuid")));
                                }
                                uuids.remove(null);
                                stats.incrementAffectedUsersBy(uuids.size());
                            }
                        }
                        stats.incrementAffectedNodesBy(ps.executeUpdate());
                    } else {
                        ps.execute();
                    }
                }
            }

            if (bulkUpdate.getDataType().isIncludingGroups()) {
                String table = this.statementProcessor.apply("{prefix}group_permissions");
                try (PreparedStatement ps = bulkUpdate.buildAsSql().build(c, q -> q.replace("{table}", table))) {

                    if (bulkUpdate.isTrackingStatistics()) {
                        PreparedStatementBuilder builder = new PreparedStatementBuilder();
                        builder.append(GROUP_PERMISSIONS_SELECT_ALL);
                        if (!bulkUpdate.getQueries().isEmpty()) {
                            builder.append(" WHERE ");
                            bulkUpdate.getQueries().forEach(query -> query.appendSql(builder));
                        }

                        try (PreparedStatement lookup = builder.build(c, this.statementProcessor)) {
                            try (ResultSet rs = lookup.executeQuery()) {
                                Set<String> groups = new HashSet<>();

                                while (rs.next()) {
                                    groups.add(rs.getString("name"));
                                }
                                groups.remove(null);
                                stats.incrementAffectedGroupsBy(groups.size());
                            }
                        }
                        stats.incrementAffectedNodesBy(ps.executeUpdate());
                    } else {
                        ps.execute();
                    }
                }
            }
        }
    }

    @Override
    public User loadUser(UUID uniqueId, String username) throws SQLException {
        User user = this.plugin.getUserManager().getOrMake(uniqueId, username);
        user.getIoLock().lock();
        try {
            List<SqlNode> nodes;
            String primaryGroup = null;
            String savedUsername = null;

            try (Connection c = this.connectionFactory.getConnection()) {
                nodes = selectUserPermissions(new ArrayList<>(), c, user.getUniqueId());

                SqlPlayerData playerData = selectPlayerData(c, user.getUniqueId());
                if (playerData != null) {
                    primaryGroup = playerData.primaryGroup;
                    savedUsername = playerData.username;
                }
            }

            // update username & primary group
            if (primaryGroup == null) {
                primaryGroup = GroupManager.DEFAULT_GROUP_NAME;
            }
            user.getPrimaryGroup().setStoredValue(primaryGroup);

            // Update their username to what was in the storage if the one in the local instance is null
            user.setUsername(savedUsername, true);

            if (!nodes.isEmpty()) {
                user.setNodes(DataType.NORMAL, nodes.stream().map(SqlNode::toNode));

                // Save back to the store if data they were given any defaults or had permissions expire
                if (this.plugin.getUserManager().giveDefaultIfNeeded(user, false) | user.auditTemporaryNodes()) {
                    // This should be fine, as the lock will be acquired by the same thread.
                    saveUser(user);
                }

            } else {
                if (this.plugin.getUserManager().shouldSave(user)) {
                    user.clearNodes(DataType.NORMAL, null, true);
                    user.getPrimaryGroup().setStoredValue(null);
                    this.plugin.getUserManager().giveDefaultIfNeeded(user, false);
                }
            }
        } finally {
            user.getIoLock().unlock();
        }
        return user;
    }

    @Override
    public void saveUser(User user) throws SQLException {
        user.getIoLock().lock();
        try {
            if (!this.plugin.getUserManager().shouldSave(user)) {
                try (Connection c = this.connectionFactory.getConnection()) {
                    deleteUser(c, user.getUniqueId());
                }
                return;
            }

            Set<SqlNode> remote;
            try (Connection c = this.connectionFactory.getConnection()) {
                remote = selectUserPermissions(new HashSet<>(), c, user.getUniqueId());
            }

            Set<SqlNode> local = user.normalData().asList().stream().map(SqlNode::fromNode).collect(Collectors.toSet());
            Set<SqlNode> missingFromRemote = getMissingFromRemote(local, remote);
            Set<SqlNode> missingFromLocal = getMissingFromLocal(local, remote);

            try (Connection c = this.connectionFactory.getConnection()) {
                updateUserPermissions(c, user.getUniqueId(), missingFromRemote, missingFromLocal);
                insertPlayerData(c, user.getUniqueId(), new SqlPlayerData(
                        user.getPrimaryGroup().getStoredValue().orElse(GroupManager.DEFAULT_GROUP_NAME),
                        user.getUsername().orElse("null").toLowerCase()
                ));
            }
        } finally {
            user.getIoLock().unlock();
        }
    }

    @Override
    public Set<UUID> getUniqueUsers() throws SQLException {
        Set<UUID> uuids = new HashSet<>();
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(USER_PERMISSIONS_SELECT_DISTINCT))) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = Uuids.fromString(rs.getString("uuid"));
                        if (uuid != null) {
                            uuids.add(uuid);
                        }
                    }
                }
            }
        }
        return uuids;
    }

    @Override
    public <N extends Node> List<NodeEntry<UUID, N>> searchUserNodes(ConstraintNodeMatcher<N> constraint) throws SQLException {
        PreparedStatementBuilder builder = new PreparedStatementBuilder().append(USER_PERMISSIONS_SELECT_PERMISSION);
        constraint.getConstraint().appendSql(builder, "permission");

        List<NodeEntry<UUID, N>> held = new ArrayList<>();
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = builder.build(c, this.statementProcessor)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID holder = UUID.fromString(rs.getString("uuid"));
                        Node node = readNode(rs).toNode();

                        N match = constraint.filterConstraintMatch(node);
                        if (match != null) {
                            held.add(NodeEntry.of(holder, match));
                        }
                    }
                }
            }
        }
        return held;
    }


    @Override
    public Group createAndLoadGroup(String name) throws SQLException {
        String query = GROUP_INSERT.getOrDefault(this.connectionFactory.getImplementationName(), GROUP_INSERT_DEFAULT);
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(query))) {
                ps.setString(1, name);
                ps.execute();
            }
        }

        return loadGroup(name).get();
    }

    @Override
    public Optional<Group> loadGroup(String name) throws SQLException {
        Set<String> groups;
        try (Connection c = this.connectionFactory.getConnection()) {
            groups = selectGroups(c);
        }

        if (!groups.contains(name)) {
            return Optional.empty();
        }

        Group group = this.plugin.getGroupManager().getOrMake(name);
        group.getIoLock().lock();
        try {
            List<SqlNode> nodes;
            try (Connection c = this.connectionFactory.getConnection()) {
                nodes = selectGroupPermissions(new ArrayList<>(), c, group.getName());
            }

            if (!nodes.isEmpty()) {
                group.setNodes(DataType.NORMAL, nodes.stream().map(SqlNode::toNode));
            } else {
                group.clearNodes(DataType.NORMAL, null, false);
            }
        } finally {
            group.getIoLock().unlock();
        }
        return Optional.of(group);
    }

    @Override
    public void loadAllGroups() throws SQLException {
        Map<String, Collection<SqlNode>> groups = new HashMap<>();
        try (Connection c = this.connectionFactory.getConnection()) {
            selectGroups(c).forEach(name -> groups.put(name, new ArrayList<>()));
            selectAllGroupPermissions(groups, c);
        }

        for (Map.Entry<String, Collection<SqlNode>> entry : groups.entrySet()) {
            Group group = this.plugin.getGroupManager().getOrMake(entry.getKey());
            group.getIoLock().lock();
            try {
                Collection<SqlNode> nodes = entry.getValue();
                if (!nodes.isEmpty()) {
                    group.setNodes(DataType.NORMAL, nodes.stream().map(SqlNode::toNode));
                } else {
                    group.clearNodes(DataType.NORMAL, null, false);
                }
            } finally {
                group.getIoLock().unlock();
            }
        }

        this.plugin.getGroupManager().retainAll(groups.keySet());
    }

    @Override
    public void saveGroup(Group group) throws SQLException {
        group.getIoLock().lock();
        try {
            if (group.normalData().isEmpty()) {
                try (Connection c = this.connectionFactory.getConnection()) {
                    deleteGroupPermissions(c, group.getName());
                }
                return;
            }

            Set<SqlNode> remote;
            try (Connection c = this.connectionFactory.getConnection()) {
                remote = selectGroupPermissions(new HashSet<>(), c, group.getName());
            }

            Set<SqlNode> local = group.normalData().asList().stream().map(SqlNode::fromNode).collect(Collectors.toSet());
            Set<SqlNode> missingFromRemote = getMissingFromRemote(local, remote);
            Set<SqlNode> missingFromLocal = getMissingFromLocal(local, remote);

            if (!missingFromLocal.isEmpty() || !missingFromRemote.isEmpty()) {
                try (Connection c = this.connectionFactory.getConnection()) {
                    updateGroupPermissions(c, group.getName(), missingFromRemote, missingFromLocal);
                }
            }
        } finally {
            group.getIoLock().unlock();
        }
    }

    @Override
    public void deleteGroup(Group group) throws SQLException {
        group.getIoLock().lock();
        try {
            try (Connection c = this.connectionFactory.getConnection()) {
                deleteGroupPermissions(c, group.getName());

                try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(GROUP_DELETE))) {
                    ps.setString(1, group.getName());
                    ps.execute();
                }
            }
        } finally {
            group.getIoLock().unlock();
        }

        this.plugin.getGroupManager().unload(group.getName());
    }

    @Override
    public <N extends Node> List<NodeEntry<String, N>> searchGroupNodes(ConstraintNodeMatcher<N> constraint) throws SQLException {
        PreparedStatementBuilder builder = new PreparedStatementBuilder().append(GROUP_PERMISSIONS_SELECT_PERMISSION);
        constraint.getConstraint().appendSql(builder, "permission");

        List<NodeEntry<String, N>> held = new ArrayList<>();
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = builder.build(c, this.statementProcessor)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String holder = rs.getString("name");
                        Node node = readNode(rs).toNode();

                        N match = constraint.filterConstraintMatch(node);
                        if (match != null) {
                            held.add(NodeEntry.of(holder, match));
                        }
                    }
                }
            }
        }
        return held;
    }

    @Override
    public Track createAndLoadTrack(String name) throws SQLException {
        Track track = this.plugin.getTrackManager().getOrMake(name);
        track.getIoLock().lock();
        try {
            List<String> groups;
            try (Connection c = this.connectionFactory.getConnection()) {
                groups = selectTrack(c, track.getName());
            }

            if (groups != null) {
                track.setGroups(groups);
            } else {
                try (Connection c = this.connectionFactory.getConnection()) {
                    insertTrack(c, track.getName(), track.getGroups());
                }
            }
        } finally {
            track.getIoLock().unlock();
        }
        return track;
    }

    @Override
    public Optional<Track> loadTrack(String name) throws SQLException {
        Set<String> tracks;
        try (Connection c = this.connectionFactory.getConnection()) {
            tracks = selectTracks(c);
        }

        if (!tracks.contains(name)) {
            return Optional.empty();
        }

        Track track = this.plugin.getTrackManager().getOrMake(name);
        track.getIoLock().lock();
        try {
            List<String> groups;
            try (Connection c = this.connectionFactory.getConnection()) {
                groups = selectTrack(c, name);
            }

            track.setGroups(groups);
        } finally {
            track.getIoLock().unlock();
        }
        return Optional.of(track);
    }

    @Override
    public void loadAllTracks() throws SQLException {
        Set<String> tracks;
        try (Connection c = this.connectionFactory.getConnection()) {
            tracks = selectTracks(c);

            for (String trackName : tracks) {
                Track track = this.plugin.getTrackManager().getOrMake(trackName);
                track.getIoLock().lock();
                try {
                    List<String> groups = selectTrack(c, trackName);
                    track.setGroups(groups);
                } finally {
                    track.getIoLock().unlock();
                }
            }
        }

        this.plugin.getTrackManager().retainAll(tracks);
    }

    @Override
    public void saveTrack(Track track) throws SQLException {
        track.getIoLock().lock();
        try {
            try (Connection c = this.connectionFactory.getConnection()) {
                updateTrack(c, track.getName(), track.getGroups());
            }
        } finally {
            track.getIoLock().unlock();
        }
    }

    @Override
    public void deleteTrack(Track track) throws SQLException {
        track.getIoLock().lock();
        try {
            try (Connection c = this.connectionFactory.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(TRACK_DELETE))) {
                    ps.setString(1, track.getName());
                    ps.execute();
                }
            }
        } finally {
            track.getIoLock().unlock();
        }

        this.plugin.getTrackManager().unload(track.getName());
    }

    @Override
    public PlayerSaveResult savePlayerData(UUID uniqueId, String username) throws SQLException {
        username = username.toLowerCase();

        // find any existing mapping
        String oldUsername = getPlayerName(uniqueId);

        // do the insert
        if (!username.equals(oldUsername)) {
            try (Connection c = this.connectionFactory.getConnection()) {
                if (oldUsername != null) {
                    try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_UPDATE_USERNAME_FOR_UUID))) {
                        ps.setString(1, username);
                        ps.setString(2, uniqueId.toString());
                        ps.execute();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_INSERT))) {
                        ps.setString(1, uniqueId.toString());
                        ps.setString(2, username);
                        ps.setString(3, GroupManager.DEFAULT_GROUP_NAME);
                        ps.execute();
                    }
                }
            }
        }

        PlayerSaveResultImpl result = PlayerSaveResultImpl.determineBaseResult(username, oldUsername);

        Set<UUID> conflicting = new HashSet<>();
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_SELECT_ALL_UUIDS_BY_USERNAME))) {
                ps.setString(1, username);
                ps.setString(2, uniqueId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        conflicting.add(UUID.fromString(rs.getString("uuid")));
                    }
                }
            }
        }

        if (!conflicting.isEmpty()) {
            // remove the mappings for conflicting uuids
            try (Connection c = this.connectionFactory.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_DELETE_ALL_UUIDS_BY_USERNAME))) {
                    ps.setString(1, username);
                    ps.setString(2, uniqueId.toString());
                    ps.execute();
                }
            }
            result = result.withOtherUuidsPresent(conflicting);
        }

        return result;
    }

    @Override
    public void deletePlayerData(UUID uniqueId) throws SQLException {
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_DELETE))) {
                ps.setString(1, uniqueId.toString());
                ps.execute();
            }
        }
    }

    @Override
    public UUID getPlayerUniqueId(String username) throws SQLException {
        username = username.toLowerCase();
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_SELECT_UUID_BY_USERNAME))) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("uuid"));
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String getPlayerName(UUID uniqueId) throws SQLException {
        try (Connection c = this.connectionFactory.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_SELECT_USERNAME_BY_UUID))) {
                ps.setString(1, uniqueId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("username");
                    }
                }
            }
        }
        return null;
    }

    private static void writeAction(Action action, PreparedStatement ps) throws SQLException {
        ps.setLong(1, action.getTimestamp().getEpochSecond());
        ps.setString(2, action.getSource().getUniqueId().toString());
        ps.setString(3, action.getSource().getName());
        ps.setString(4, Character.toString(LoggedAction.getTypeCharacter(action.getTarget().getType())));
        ps.setString(5, action.getTarget().getUniqueId().map(UUID::toString).orElse("null"));
        ps.setString(6, action.getTarget().getName());
        ps.setString(7, action.getDescription());
    }

    private static LoggedAction readAction(ResultSet rs) throws SQLException {
        final String actedUuid = rs.getString("acted_uuid");
        return LoggedAction.build()
                .timestamp(Instant.ofEpochSecond(rs.getLong("time")))
                .source(UUID.fromString(rs.getString("actor_uuid")))
                .sourceName(rs.getString("actor_name"))
                .targetType(LoggedAction.parseTypeCharacter(rs.getString("type").toCharArray()[0]))
                .target(actedUuid.equals("null") ? null : UUID.fromString(actedUuid))
                .targetName(rs.getString("acted_name"))
                .description(rs.getString("action"))
                .build();
    }

    private static SqlNode readNode(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String permission = rs.getString("permission");
        boolean value = rs.getBoolean("value");
        String server = rs.getString("server");
        String world = rs.getString("world");
        long expiry = rs.getLong("expiry");
        String contexts = rs.getString("contexts");
        return SqlNode.fromSqlFields(id, permission, value, server, world, expiry, contexts);
    }

    private static void writeNode(SqlNode nd, PreparedStatement ps) throws SQLException {
        ps.setString(2, nd.getPermission());
        ps.setBoolean(3, nd.getValue());
        ps.setString(4, nd.getServer());
        ps.setString(5, nd.getWorld());
        ps.setLong(6, nd.getExpiry());
        ps.setString(7, GsonProvider.normal().toJson(ContextSetJsonSerializer.serialize(nd.getContexts())));
    }

    private static Set<SqlNode> getMissingFromRemote(Set<SqlNode> local, Set<SqlNode> remote) {
        // entries in local but not remote need to be added
        Set<SqlNode> missingFromRemote = new HashSet<>(local);
        missingFromRemote.removeAll(remote);
        return missingFromRemote;
    }

    private static Set<SqlNode> getMissingFromLocal(Set<SqlNode> local, Set<SqlNode> remote) {
        // entries in remote but not local need to be removed
        Set<SqlNode> missingFromLocal = new HashSet<>(remote);
        missingFromLocal.removeAll(local);
        return missingFromLocal;
    }

    private <T extends Collection<SqlNode>> T selectUserPermissions(T nodes, Connection c, UUID user) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(USER_PERMISSIONS_SELECT))) {
            ps.setString(1, user.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nodes.add(readNode(rs));
                }
            }
        }
        return nodes;
    }

    private SqlPlayerData selectPlayerData(Connection c, UUID user) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_SELECT_BY_UUID))) {
            ps.setString(1, user.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SqlPlayerData(rs.getString("primary_group"), rs.getString("username"));
                } else {
                    return null;
                }
            }
        }
    }

    private void deleteUser(Connection c, UUID user) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(USER_PERMISSIONS_DELETE))) {
            ps.setString(1, user.toString());
            ps.execute();
        }
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_UPDATE_PRIMARY_GROUP_BY_UUID))) {
            ps.setString(1, GroupManager.DEFAULT_GROUP_NAME);
            ps.setString(2, user.toString());
            ps.execute();
        }
    }

    private void updateUserPermissions(Connection c, UUID user, Set<SqlNode> add, Set<SqlNode> delete) throws SQLException {
        if (!delete.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(USER_PERMISSIONS_DELETE_SPECIFIC))) {
                for (SqlNode node : delete) {
                    ps.setLong(1, node.getSqlId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (!add.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(USER_PERMISSIONS_INSERT))) {
                for (SqlNode node : add) {
                    ps.setString(1, user.toString());
                    writeNode(node, ps);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private void insertPlayerData(Connection c, UUID user, SqlPlayerData data) throws SQLException {
        boolean hasPrimaryGroupSaved;
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_SELECT_PRIMARY_GROUP_BY_UUID))) {
            ps.setString(1, user.toString());
            try (ResultSet rs = ps.executeQuery()) {
                hasPrimaryGroupSaved = rs.next();
            }
        }

        if (hasPrimaryGroupSaved) {
            // update
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_UPDATE_PRIMARY_GROUP_BY_UUID))) {
                ps.setString(1, data.primaryGroup);
                ps.setString(2, user.toString());
                ps.execute();
            }
        } else {
            // insert
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(PLAYER_INSERT))) {
                ps.setString(1, user.toString());
                ps.setString(2, data.username);
                ps.setString(3, data.primaryGroup);
                ps.execute();
            }
        }
    }

    private Set<String> selectGroups(Connection c) throws SQLException {
        Set<String> groups = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(GROUP_SELECT_ALL))) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groups.add(rs.getString("name").toLowerCase());
                }
            }
        }
        return groups;
    }

    private <T extends Collection<SqlNode>> T selectGroupPermissions(T nodes, Connection c, String group) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(GROUP_PERMISSIONS_SELECT))) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nodes.add(readNode(rs));
                }
            }
        }
        return nodes;
    }

    private void selectAllGroupPermissions(Map<String, Collection<SqlNode>> nodes, Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(GROUP_PERMISSIONS_SELECT_ALL))) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String holder = rs.getString("name");
                    Collection<SqlNode> list = nodes.get(holder);
                    if (list != null) {
                        list.add(readNode(rs));
                    }
                }
            }
        }
    }

    private void deleteGroupPermissions(Connection c, String group) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(GROUP_PERMISSIONS_DELETE))) {
            ps.setString(1, group);
            ps.execute();
        }
    }

    private void updateGroupPermissions(Connection c, String group, Set<SqlNode> add, Set<SqlNode> delete) throws SQLException {
        if (!delete.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(GROUP_PERMISSIONS_DELETE_SPECIFIC))) {
                for (SqlNode node : delete) {
                    ps.setLong(1, node.getSqlId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (!add.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(GROUP_PERMISSIONS_INSERT))) {
                for (SqlNode node : add) {
                    ps.setString(1, group);
                    writeNode(node, ps);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private List<String> selectTrack(Connection c, String name) throws SQLException {
        String groups;
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(TRACK_SELECT))) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    groups = rs.getString("groups");
                } else {
                    groups = null;
                }
            }
        }
        return groups == null ? null : GsonProvider.normal().fromJson(groups, LIST_STRING_TYPE);
    }

    private void insertTrack(Connection c, String name, List<String> groups) throws SQLException {
        String json = GsonProvider.normal().toJson(groups);
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(TRACK_INSERT))) {
            ps.setString(1, name);
            ps.setString(2, json);
            ps.execute();
        }
    }

    private void updateTrack(Connection c, String name, List<String> groups) throws SQLException {
        String json = GsonProvider.normal().toJson(groups);
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(TRACK_UPDATE))) {
            ps.setString(1, json);
            ps.setString(2, name);
            ps.execute();
        }
    }

    private Set<String> selectTracks(Connection c) throws SQLException {
        Set<String> tracks = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(this.statementProcessor.apply(TRACK_SELECT_ALL))) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tracks.add(rs.getString("name").toLowerCase());
                }
            }
        }
        return tracks;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, "%", null)) {
            while (rs.next()) {
                if (rs.getString(3).equalsIgnoreCase(table)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class SqlPlayerData {
        private final String primaryGroup;
        private final String username;

        SqlPlayerData(String primaryGroup, String username) {
            this.primaryGroup = primaryGroup;
            this.username = username;
        }
    }

}
