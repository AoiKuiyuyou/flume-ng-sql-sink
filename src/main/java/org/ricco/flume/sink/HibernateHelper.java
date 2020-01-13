package org.ricco.flume.sink;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.hibernate.*;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.GenericJDBCException;
import org.hibernate.exception.SQLGrammarException;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.transform.Transformers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flume.Context;

/**
 * Helper class to manage hibernate sessions and perform queries
 * 
 * @author <a href="mailto:riccosir@qq.com">Ricco</a>
 *
 */
public class HibernateHelper {

	private static final Logger LOG = LoggerFactory
			.getLogger(HibernateHelper.class);

	private static SessionFactory factory;
	private Session session;
	private ServiceRegistry serviceRegistry;
	private Configuration config;
	private SQLSinkHelper sqlSinkHelper;

	/**
	 * Constructor to initialize hibernate configuration parameters
	 * @param sqlSinkHelper Contains the configuration parameters from flume config file
	 */
	public HibernateHelper(SQLSinkHelper sqlSinkHelper) {

		this.sqlSinkHelper = sqlSinkHelper;
		Context context = sqlSinkHelper.getContext();

		/* check for mandatory propertis */
		sqlSinkHelper.checkMandatoryProperties();

		Map<String,String> hibernateProperties = context.getSubProperties("hibernate.");
		Iterator<Map.Entry<String,String>> it = hibernateProperties.entrySet().iterator();
		
		config = new Configuration();
		Map.Entry<String, String> e;
		
		while (it.hasNext()){
			e = it.next();
			config.setProperty("hibernate." + e.getKey(), e.getValue());
		}

	}

	/**
	 * Connect to database using hibernate
	 */
	public void establishSession() {

		LOG.info("Opening hibernate session");

		serviceRegistry = new StandardServiceRegistryBuilder()
				.applySettings(config.getProperties()).build();
		factory = config.buildSessionFactory(serviceRegistry);
		session = factory.openSession();
		session.setCacheMode(CacheMode.IGNORE);
	}

	/**
	 * Close database connection
	 */
	public void closeSession() {

		LOG.info("Closing hibernate session");

		session.close();
		factory.close();
	}

	private void buildQueryValues(Query query, List<String[]> lines)
	{
		String[] parameters = query.getNamedParameters();
		for(String param : parameters) {
			String[] ids = param.split("_");
			try {
				int lineIndex = Integer.parseInt(ids[0].substring(1));
				int index = Integer.parseInt(ids[1]);
				query.setString(param, lines.get(lineIndex)[index]);
			} catch (Exception e) {
				LOG.error(e.toString());
			}
		}
	}

	private int queryWithinTable(String tableName, List<String[]> linesWithinTable) throws InterruptedException {
		int finishCount = 0;

		LOG.info("queryWithinTable " + tableName + " " + linesWithinTable.size());
		if(linesWithinTable.size() > 0) {
			SQLQuery sqlQuery = session.createSQLQuery(sqlSinkHelper.buildInsertQuery(tableName,linesWithinTable));
			buildQueryValues(sqlQuery, linesWithinTable);
			int queryResult = query(sqlQuery);
			if (queryResult == 1) {
				// Create table then retry
				LOG.warn("Create table " + tableName);
				if (0 == query(session.createSQLQuery(sqlSinkHelper.buildCreateQuery(linesWithinTable.get(0))))) {
					queryResult = query(sqlQuery);
					if (queryResult == 0) finishCount += linesWithinTable.size();
				}
			}

			if (queryResult != 0) {
				int retryCount = 0;
				if (linesWithinTable.size() > 1) {
					// Once a half
					int firstHalf = linesWithinTable.size() / 2;
					int secondHalf = linesWithinTable.size() - firstHalf;
					LOG.info("Divide into two halves. First " + firstHalf + " lines");
					retryCount += queryWithinTable(tableName, linesWithinTable.subList(0, firstHalf));
					LOG.info("Last " + secondHalf + " lines");
					retryCount += queryWithinTable(tableName, linesWithinTable.subList(firstHalf, linesWithinTable.size()));
				} else {
					if (queryResult == 2) {
						// Try update
						LOG.warn("Update data: " + String.join(",", linesWithinTable.get(0)));
						sqlQuery = session.createSQLQuery(sqlSinkHelper.buildUpdateQuery(tableName, linesWithinTable.get(0)));
						buildQueryValues(sqlQuery, linesWithinTable.subList(0 , 1));
						queryResult = query(sqlQuery);
						if(queryResult == 0) retryCount = 1;
					}

					if(queryResult != 0) {
						LOG.error("Data loss: " + String.join(",", linesWithinTable.get(0)));
					}
				}

				finishCount += retryCount;
			}
		}

		return finishCount;
	}

	/**
	 * Execute the selection query in the database
	 * @return The query result. Each Object is a cell content. <p>
	 * The cell contents use database types (date,int,string...), 
	 * keep in mind in case of future conversions/castings.
	 * @throws InterruptedException 
	 */
	@SuppressWarnings("unchecked")
	public int executeQuery(List<String[]> lines) throws InterruptedException {

		int finishCount = 0;
		String previosTableName = null;
		
		if (!session.isConnected()){
			resetConnection();
		}

		List<String[]> linesWithinTable = new ArrayList<>();
		for(String[] line : lines) {

			String tableName = sqlSinkHelper.buildTableName(line);

			if(!tableName.equals(previosTableName)) {
				if(previosTableName != null && linesWithinTable.size() > 0) {
					finishCount += queryWithinTable(previosTableName, linesWithinTable);
					linesWithinTable.clear();
				}
				previosTableName = tableName;
			}

			linesWithinTable.add(line);
		}

		if(linesWithinTable.size() > 0)
			finishCount += queryWithinTable(previosTableName, linesWithinTable);

		return finishCount;
	}

	private void resetConnection() throws InterruptedException{
		LOG.info("resetConnection");
		if(session.isOpen()){
			session.close();
			factory.close();
		} else {
			establishSession();
		}
		
	}

	private int query(Query query) throws InterruptedException {
		if(query.getQueryString().length() > 0) {
			LOG.info("Query start");
			try {
				query.executeUpdate();
			} catch (SQLGrammarException e) {
				return 1;
			} catch (ConstraintViolationException e) {
				return 2;
			} catch (GenericJDBCException e) {
				if(e.getErrorCode() == 0) return 0;
				LOG.error("JDBCException thrown, resetting connection.", e);
				resetConnection();
				return -1;
			} catch (Exception e) {
				LOG.error("Exception thrown, resetting connection.", e);
				resetConnection();
				return -1;
			} finally {
				LOG.info("Query end");
			}
		}
        return 0;
    }
}
