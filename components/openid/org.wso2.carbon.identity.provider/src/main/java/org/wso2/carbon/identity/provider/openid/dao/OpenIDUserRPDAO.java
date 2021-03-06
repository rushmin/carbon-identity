/*
 * Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * 
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.provider.openid.dao;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.model.OpenIDUserRPDO;
import org.wso2.carbon.identity.core.persistence.JDBCPersistenceManager;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;

public class OpenIDUserRPDAO {

    protected Log log = LogFactory.getLog(OpenIDUserRPDAO.class);

    /**
     * Creates a Relying Party and associates it with the User.
     * If the entry exist, then update with the new data
     *
     * @param rpdo
     * @throws IdentityException
     */
    public void createOrUpdate(OpenIDUserRPDO rpdo) throws IdentityException {

        // first we try to get DO from the database. Return null if no data
        OpenIDUserRPDO existingdo = getOpenIDUserRP(rpdo.getUserName(), rpdo.getRpUrl());

        Connection connection = null;
        PreparedStatement prepStmt = null;

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();

            if (existingdo != null) { // data found in the database
                // we should update the entry
                prepStmt = connection.prepareStatement(OpenIDSQLQueries.UPDATE_USER_RP);

                prepStmt.setString(5, rpdo.getUserName());
                prepStmt.setInt(6, IdentityUtil.getTenantIdOFUser(rpdo.getUserName()));
                prepStmt.setString(7, rpdo.getRpUrl());
                prepStmt.setString(1, rpdo.isTrustedAlways() ? "TRUE" : "FALSE");

                // we set the new current date
                prepStmt.setDate(2, new java.sql.Date(new Date().getTime()));
                // we increment the value which is in the database
                prepStmt.setInt(3, existingdo.getVisitCount() + 1); // increase visit count

                prepStmt.setString(4, rpdo.getDefaultProfileName());

                prepStmt.execute();
                connection.commit();
            } else {
                // data not found, we should create the entry
                prepStmt = connection.prepareStatement(OpenIDSQLQueries.STORE_USER_RP);

                prepStmt.setString(1, rpdo.getUserName());
                prepStmt.setInt(2, IdentityUtil.getTenantIdOFUser(rpdo.getUserName()));
                prepStmt.setString(3, rpdo.getRpUrl());
                prepStmt.setString(4, rpdo.isTrustedAlways() ? "TRUE" : "FALSE");

                // we set the current date
                prepStmt.setDate(5, new java.sql.Date(new Date().getTime()));
                // ok, this is the first visit
                prepStmt.setInt(6, 1);

                prepStmt.setString(7, rpdo.getDefaultProfileName());

                prepStmt.execute();
                connection.commit();
            }
        } catch (SQLException e) {
            log.error("Failed to store RP:  " + rpdo.getRpUrl() + " for user: " +
                    rpdo.getUserName() + " Error while accessing the database", e);
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
    }

    /**
     * Updates the Relying Party if exists, if not, then creates a new Relying
     * Party
     *
     * @param rpdo
     * @throws IdentityException
     */
    public void update(OpenIDUserRPDO rpdo) throws IdentityException {

        Connection connection = null;
        PreparedStatement prepStmt = null;

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();

            if (isUserRPExist(connection, rpdo)) {
                // we should update the entry
                prepStmt = connection.prepareStatement(OpenIDSQLQueries.UPDATE_USER_RP);

                prepStmt.setString(1, rpdo.getUserName());
                prepStmt.setInt(2, IdentityUtil.getTenantIdOFUser(rpdo.getUserName()));
                prepStmt.setString(3, rpdo.getRpUrl());
                prepStmt.setString(4, rpdo.isTrustedAlways() ? "TRUE" : "FALSE");
                prepStmt.setDate(5, new java.sql.Date(rpdo.getLastVisit().getTime()));
                prepStmt.setInt(6, rpdo.getVisitCount() + 1);
                prepStmt.setString(7, rpdo.getDefaultProfileName());

                prepStmt.execute();
                connection.commit();
            } else {
                // we should create the entry
                log.debug("Failed to update RP: " + rpdo.getRpUrl() + " for user: " +
                        rpdo.getUserName() + " Entry does not exist in the databse.");
            }
        } catch (SQLException e) {
            log.error("Failed to update RP:  " + rpdo.getRpUrl() + " for user: " +
                    rpdo.getUserName() + " Error while accessing the database", e);
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
    }

    /**
     * Remove the entry from the database.
     *
     * @param opdo
     * @throws IdentityException
     */
    public void delete(OpenIDUserRPDO opdo) throws IdentityException {

        Connection connection = null;
        PreparedStatement prepStmt = null;

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();

            if (isUserRPExist(connection, opdo)) {
                prepStmt = connection.prepareStatement(OpenIDSQLQueries.REMOVE_USER_RP);
                prepStmt.setString(1, opdo.getUserName());
                prepStmt.setInt(2, IdentityUtil.getTenantIdOFUser(opdo.getUserName()));
                prepStmt.setString(3, opdo.getRpUrl());
                prepStmt.execute();
                connection.commit();
            }

        } catch (SQLException e) {
            log.error("Failed to remove RP: " + opdo.getRpUrl() + " of user: " +
                    opdo.getUserName() + ". Error while accessing the database.");
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
    }

    /**
     * Returns relying party user settings corresponding to a given user name.
     *
     * @param userName Unique user name
     * @param rpUrl    Relying party urlupdateOpenIDUserRPInfo
     * @return A set of OpenIDUserRPDO, corresponding to the provided user name
     * and RP url.
     * @throws IdentityException
     */
    public OpenIDUserRPDO getOpenIDUserRP(String userName, String rpUrl) throws IdentityException {

        Connection connection = null;
        PreparedStatement prepStmt = null;
        OpenIDUserRPDO rpdo = new OpenIDUserRPDO();
        rpdo.setUserName(userName);
        rpdo.setRpUrl(rpUrl);

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();

            if (isUserRPExist(connection, rpdo)) {
                prepStmt = connection.prepareStatement(OpenIDSQLQueries.LOAD_USER_RP);
                prepStmt.setString(1, userName);
                prepStmt.setInt(2, IdentityUtil.getTenantIdOFUser(userName));
                prepStmt.setString(3, rpUrl);
                return buildUserRPDO(prepStmt.executeQuery(), userName);
            } else {
                log.debug("RP: " + rpUrl + " of user: " + userName + " not found in the database");
            }
        } catch (SQLException e) {
            log.error("Failed to load RP: " + rpUrl + " for user: " + userName +
                    ". Error while accessing the database.", e);
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
        return null;
    }

    /**
     * Returns all registered relying parties
     *
     * @return
     * @throws IdentityException
     */
    public OpenIDUserRPDO[] getAllOpenIDUserRP() throws IdentityException {
        Connection connection = null;
        PreparedStatement prepStmt = null;
        ResultSet results = null;
        OpenIDUserRPDO[] rpDOs = null;
        ArrayList<OpenIDUserRPDO> rpdos = new ArrayList<OpenIDUserRPDO>();

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            prepStmt = connection.prepareStatement(OpenIDSQLQueries.LOAD_ALL_USER_RPS);
            results = prepStmt.executeQuery();

            while (results.next()) {
                OpenIDUserRPDO rpdo = new OpenIDUserRPDO();
                rpdo.setUserName(results.getString(1));
                rpdo.setRpUrl(results.getString(3));
                rpdo.setTrustedAlways(Boolean.parseBoolean(results.getString(4)));
                rpdo.setLastVisit(results.getDate(5));
                rpdo.setVisitCount(results.getInt(6));
                rpdo.setDefaultProfileName(results.getString(7));
                rpdos.add(rpdo);
            }

            rpDOs = new OpenIDUserRPDO[rpdos.size()];
            rpDOs = rpdos.toArray(rpDOs);

        } catch (SQLException e) {
            log.error("Error while accessing the database to load RPs.", e);
        } finally {
            IdentityDatabaseUtil.closeResultSet(results);
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
        return rpDOs;
    }

    /**
     * Returns relying party user settings corresponding to a given user name.
     *
     * @param userName Unique user name
     * @return OpenIDUserRPDO, corresponding to the provided user name and RP
     * url.
     * @throws IdentityException
     */
    public OpenIDUserRPDO[] getOpenIDUserRPs(String userName) throws IdentityException {

        Connection connection = null;
        PreparedStatement prepStmt = null;
        ResultSet results = null;
        OpenIDUserRPDO[] rpDOs = null;
        ArrayList<OpenIDUserRPDO> rpdos = new ArrayList<OpenIDUserRPDO>();

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            prepStmt = connection.prepareStatement(OpenIDSQLQueries.LOAD_USER_RPS);
            prepStmt.setString(1, userName);
            prepStmt.setInt(2, IdentityUtil.getTenantIdOFUser(userName));
            results = prepStmt.executeQuery();

            while (results.next()) {
                OpenIDUserRPDO rpdo = new OpenIDUserRPDO();
                rpdo.setUserName(results.getString(1));
                rpdo.setRpUrl(results.getString(3));
                rpdo.setTrustedAlways(Boolean.parseBoolean(results.getString(4)));
                rpdo.setLastVisit(results.getDate(5));
                rpdo.setVisitCount(results.getInt(6));
                rpdo.setDefaultProfileName(results.getString(7));
                rpdos.add(rpdo);
            }

            rpDOs = new OpenIDUserRPDO[rpdos.size()];
            rpDOs = rpdos.toArray(rpDOs);

        } catch (SQLException e) {
            log.error("Error while accessing the database to load RPs.", e);
        } finally {
            IdentityDatabaseUtil.closeResultSet(results);
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
        return rpDOs;
    }

    /**
     * Returns the default user profile corresponding to the given user name and
     * the RP URL.
     *
     * @param userName Unique user name
     * @param rpUrl    Relying party URL
     * @return Default user profile
     * @throws IdentityException
     */
    public String getOpenIDDefaultUserProfile(String userName, String rpUrl)
            throws IdentityException {
        Connection connection = null;
        PreparedStatement prepStmt = null;

        OpenIDUserRPDO rpdo = new OpenIDUserRPDO();
        rpdo.setUserName(userName);
        rpdo.setRpUrl(rpUrl);

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();

            if (isUserRPExist(connection, rpdo)) {
                prepStmt =
                        connection.prepareStatement(OpenIDSQLQueries.LOAD_USER_RP_DEFAULT_PROFILE);
                prepStmt.setString(1, userName);
                prepStmt.setInt(2, IdentityUtil.getTenantIdOFUser(userName));
                prepStmt.setString(3, rpUrl);
                return prepStmt.executeQuery().getString(7);

            } else {
                log.debug("RP: " + rpUrl + " of user: " + userName + " not found in the database");
            }
        } catch (SQLException e) {
            log.error("Failed to load RP: " + rpUrl + " for user: " + userName +
                    ". Error while accessing the database.", e);
        } finally {
            IdentityDatabaseUtil.closeStatement(prepStmt);
            IdentityDatabaseUtil.closeConnection(connection);
        }
        return null;
    }

    /**
     * Checks if the entry exist in the database;
     *
     * @param connection
     * @param rpDo
     * @return
     * @throws SQLException
     * @throws IdentityException
     */
    private boolean isUserRPExist(Connection connection, OpenIDUserRPDO rpDo)
            throws IdentityException {

        PreparedStatement prepStmt = null;
        ResultSet results = null;
        boolean result = false;

        try {
            prepStmt = connection.prepareStatement(OpenIDSQLQueries.CHECK_USER_RP_EXIST);
            prepStmt.setString(1, rpDo.getUserName());
            prepStmt.setInt(2, IdentityUtil.getTenantIdOFUser(rpDo.getUserName()));
            prepStmt.setString(3, rpDo.getRpUrl());
            results = prepStmt.executeQuery();

            if (results != null && results.next()) {
                result = true;
            }

        } catch (SQLException e) {
            log.error("Failed to load RP: " + rpDo.getRpUrl() + " for user: " + rpDo.getUserName() +
                    ". Error while accessing the databse", e);
        } catch (RuntimeException e) {
            log.error("Error while trying to load RP : Username = " + rpDo.getUserName(), e);
        } finally {
            IdentityDatabaseUtil.closeResultSet(results);
            IdentityDatabaseUtil.closeStatement(prepStmt);

        }
        return result;
    }

    /**
     * Builds the OpenIDUserRPDO using the results
     *
     * @param results
     * @param userName
     * @return
     */
    private OpenIDUserRPDO buildUserRPDO(ResultSet results, String userName) {

        OpenIDUserRPDO rpdo = new OpenIDUserRPDO();

        try {
            if (!results.next()) {
                log.debug("RememberMe token not found for the user " + userName);
                return null;
            }

            rpdo.setUserName(results.getString(1));
            rpdo.setRpUrl(results.getString(3));
            rpdo.setTrustedAlways(Boolean.parseBoolean(results.getString(4)));
            rpdo.setLastVisit(results.getDate(5));
            rpdo.setVisitCount(results.getInt(6));
            rpdo.setDefaultProfileName(results.getString(7));

        } catch (SQLException e) {
            log.error("Error while accessing the database", e);
        }
        return rpdo;
    }

}
