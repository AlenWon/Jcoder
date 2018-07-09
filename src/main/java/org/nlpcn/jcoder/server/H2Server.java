package org.nlpcn.jcoder.server;

import com.zaxxer.hikari.HikariDataSource;
import org.h2.tools.Server;
import org.nlpcn.jcoder.util.IOUtil;
import org.nlpcn.jcoder.util.StaticValue;
import org.nlpcn.jcoder.util.StringUtil;
import org.nlpcn.jcoder.util.dao.BasicDao;
import org.nutz.mvc.NutConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.SQLException;

/**
 * h2数据库
 *
 * @author ansj
 */
public class H2Server {
	private static final Logger LOG = LoggerFactory.getLogger(H2Server.class);

	private static Server server;

	public static void startServer(NutConfig nc) {

		if (server != null && server.isRunning(true)) {
			return;
		}
		try {
			String h2db = StaticValue.HOME + "/h2db/jcoder";

			LOG.info("database path is " + h2db + " begin start...");

			boolean dbIsActive = new File(h2db + ".h2.db").isFile();

			String content = null;
			if (!dbIsActive) {
				content = IOUtil.getContent(H2Server.class.getResourceAsStream("/jcoder.sql"), IOUtil.UTF8);
				if (StringUtil.isBlank(content)) {
					LOG.error("not found sql file ");
					System.exit(-1);
				}
			}

			HikariDataSource dds = new HikariDataSource();
			dds.setDriverClassName("org.h2.Driver");
			dds.setJdbcUrl("jdbc:h2:" + h2db);
			dds.setUsername("sa");
			dds.setPassword("");
			BasicDao basicDao = new BasicDao(dds);

			StaticValue.systemDao = basicDao;

			LOG.info("database path is " + new File(h2db).getAbsolutePath());
			server = Server.createPgServer(new String[]{"-baseDir", h2db}).start();
			if (!dbIsActive) {
				LOG.warn("the database is not create , use db script to create it!");
				basicDao.executeSql(content);
			} else {
				LOG.info("the database is active good luck for use it !");
			}
		} catch (SQLException e) {
			LOG.error("start h2 fail：" + e.toString());
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		LOG.info("db has startd ， good luck !...");
	}

	public static void stopServer() {
		if (server != null) {
			System.out.println("to stop h2...");
			server.stop();
			System.out.println("stop ok.");
		}
	}

}
