package org.nlpcn.jcoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import org.nlpcn.jcoder.domain.FileInfo;
import org.nlpcn.jcoder.domain.Group;
import org.nlpcn.jcoder.domain.HostGroup;
import org.nlpcn.jcoder.filter.AuthoritiesManager;
import org.nlpcn.jcoder.service.GroupService;
import org.nlpcn.jcoder.service.ProxyService;
import org.nlpcn.jcoder.service.TaskService;
import org.nlpcn.jcoder.util.*;
import org.nlpcn.jcoder.util.dao.BasicDao;
import org.nutz.dao.Cnd;
import org.nutz.dao.Condition;
import org.nutz.http.Response;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Streams;
import org.nutz.mvc.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.net.util.URLUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

@IocBean
@Filters(@By(type = AuthoritiesManager.class))
@At("/admin/group")
@Ok("json")
public class GroupAction {

	private static final Logger LOG = LoggerFactory.getLogger(GroupAction.class);

	@Inject
	private GroupService groupService;

	@Inject
	private ProxyService proxyService;

	@Inject
	private TaskService taskService;

	private BasicDao basicDao = StaticValue.systemDao;


	@At
	public Restful list() throws Exception {
		return Restful.instance(groupService.list());
	}

	@At
	public Restful hostList() throws Exception {
		return Restful.instance(groupService.getAllHosts());
	}

	@At
	public Restful groupHostList(@Param("name") String name) throws Exception {
		return Restful.instance(groupService.getGroupHostList(name));
	}


	@At
	public Restful delete(@Param("name") String name) throws Exception {
		return null;
	}

	@At
	public Restful changeWeight(@Param("groupName") String groupName, @Param("hostPort") String hostPort, @Param("weight") Integer weight) {
		if (weight == null) {
			return Restful.instance().ok(false).msg("权重必须为正整数");
		}

		ZKMap<HostGroup> hostGroupCache = StaticValue.space().getHostGroupCache();

		HostGroup hostGroup = hostGroupCache.get(hostPort + "_" + groupName);

		if (hostGroup == null) {
			return Restful.instance().ok(false).msg("没有找到此对象");
		}

		hostGroup.setWeight(weight);

		hostGroupCache.put(hostPort + "_" + groupName, hostGroup);

		return Restful.instance().ok(true).msg(hostPort + " 更改权重为：" + weight);

	}

	@At
	public Restful diff(@Param("name") String name) {
		Condition con = Cnd.where("name", "=", name);
		int count = basicDao.searchCount(Group.class, con);
		if (count > 0) {
			return Restful.instance().ok(false).msg("组" + name + "已存在");
		}
		return Restful.instance().ok(true).msg("组" + name + "不存在");
	}

	@At
	public Restful add(@Param("hostPorts") String[] hostPorts, @Param("name") String name, @Param(value = "first", df = "true") boolean first) throws Exception {

		if (!first) {
			File file = new File(StaticValue.GROUP_FILE, name);
			file.mkdirs();
			File ioc = new File(StaticValue.GROUP_FILE, name + "/resources");
			ioc.mkdir();
			File lib = new File(StaticValue.GROUP_FILE, name + "/lib");
			lib.mkdir();

			IOUtil.Writer(new File(ioc, "ioc.js").getAbsolutePath(), "utf-8", "var ioc = {\n\t\n};");

			IOUtil.Writer(new File(lib, "pom.xml").getAbsolutePath(), "utf-8",
					"<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
							+ "	xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
							+ "	<modelVersion>4.0.0</modelVersion>\n" + "	<groupId>org.nlpcn</groupId>\n" + "	<artifactId>jcoder</artifactId>\n" + "	<version>0.1</version>\n"
							+ "	\n" + "	<dependencies>\n" + "	</dependencies>\n" + "\n" + "	<build>\n" + "		<sourceDirectory>src/main/java</sourceDirectory>\n"
							+ "		<testSourceDirectory>src/test/java</testSourceDirectory>\n" + "		\n" + "		<plugins>\n" + "			<plugin>\n"
							+ "				<artifactId>maven-compiler-plugin</artifactId>\n" + "				<version>3.3</version>\n" + "				<configuration>\n"
							+ "					<source>1.8</source>\n" + "					<target>1.8</target>\n" + "					<encoding>UTF-8</encoding>\n"
							+ "					<compilerArguments>\n" + "						<extdirs>lib</extdirs>\n" + "					</compilerArguments>\n"
							+ "				</configuration>\n" + "			</plugin>\n" + "		</plugins>\n" + "	</build>\n" + "</project>\n" + "");

			Group group = new Group();
			group.setName(name);

			basicDao.save(group);

			StaticValue.space().joinCluster();

			return Restful.instance(true, "添加成功");
		} else {

			Set<String> hostPortsArr = new HashSet<>();

			Arrays.stream(hostPorts).forEach(s -> hostPortsArr.add((String) s));

			String message = proxyService.post(hostPortsArr, "/admin/group/diff", ImmutableMap.of("name", name, "first", false), 100000, ProxyService.MERGE_FALSE_MESSAGE_CALLBACK);

			if (StringUtil.isNotBlank(message)) {
				return Restful.instance().ok(false).code(500).msg(message);
			}

			message = proxyService.post(hostPortsArr, "/admin/group/add", ImmutableMap.of("name", name, "first", false), 100000, ProxyService.MERGE_MESSAGE_CALLBACK);

			return Restful.instance().msg(message);
		}
	}

	/**
	 * 从集群中彻底删除一个group 要求group name必须没有任何一个机器使用中
	 *
	 * @param name
	 * @return
	 */
	@At
	public Restful deleteByCluster(@Param("name") String name) {
		try {
			groupService.deleteByCluster(name);
			return Restful.instance().ok(true).msg("删除 group: " + name + " 成功");
		} catch (Exception e) {
			e.printStackTrace();
			return Restful.instance().ok(false).msg("删除 group: " + name + " 失败");
		}
	}


	/**
	 * 删除group
	 *
	 * @param name
	 * @return
	 */
	@At
	public Restful delete(@Param("hostPorts") String[] hostPorts, @Param("name") String name, @Param(value = "first", df = "true") boolean first) throws Exception {

		if (!first) {
			boolean flag = groupService.deleteGroup(name);
			if (flag) {
				return Restful.instance(flag, "删除成功");
			} else {
				return Restful.instance(flag, "删除文件失败");
			}
		} else {
			Set<String> hostPortsArr = new HashSet<>();
			Arrays.stream(hostPorts).forEach(s -> hostPortsArr.add((String) s));
			String message = proxyService.post(hostPortsArr, "/admin/group/delete", ImmutableMap.of("name", name, "first", false), 100000, ProxyService.MERGE_MESSAGE_CALLBACK);
			return Restful.instance().msg(message);
		}
	}


	/**
	 * 删除group
	 *
	 * @param hostPort
	 * @return
	 */
	@At
	@Ok("void")
	public void downSDK(@Param("hostPort") String hostPort, @Param("groupName") String groupName, HttpServletResponse response) throws Exception {
		Response rep = proxyService.post(hostPort, "/admin/fileInfo/downSDK", ImmutableMap.of("groupName", groupName), 1000000);
		if (rep.getStatus() != 200) {
			throw new ApiException(rep.getStatus(), rep.getContent());
		}

		response.addHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(groupName, "utf-8") + ".zip");
		response.setContentType("application/octet-stream");

		byte[] bytes = new byte[10240];
		OutputStream os = response.getOutputStream();
		try (InputStream is = rep.getStream()) {
			while ((is.read(bytes)) != -1) {
				os.write(bytes);
			}
		}
	}


	@At
	public Restful share(@Param("hostPorts") String[] hostPorts, @Param("formHostPort") String fromHostPort, @Param("groupName") String groupName) throws Exception {
		String msg = proxyService.post(hostPorts, "/admin/group/installGroup", ImmutableMap.of("fromHostPort", fromHostPort, "groupName", groupName), 120000, ProxyService.MERGE_MESSAGE_CALLBACK);
		return Restful.instance().msg(msg);
	}


	/**
	 * 克隆一个主机的group到当前主机上
	 *
	 * @param fromHostPort
	 * @param groupName
	 * @return
	 */
	@At
	public Restful installGroup(@Param("fromHostPort") String fromHostPort, @Param("groupName") String groupName) throws Exception {
		//判断当前group是否存在
		Group group = basicDao.findByCondition(Group.class, Cnd.where("name", "=", groupName));
		if (group != null) {
			return Restful.instance(false, groupName + " 已存在！");
		}
		//获取远程主机的所有tasks

		//获取远程主机的所有files
		Response response = proxyService.post(fromHostPort, "/admin/fileInfo/listFiles", ImmutableMap.of("groupName", groupName), 120000);

		JSONArray jarry = JSONObject.parseObject(response.getContent()).getJSONArray("obj");

		for (Object o : jarry) {
			System.out.println(o);
			FileInfo fileInfo = JSONObject.toJavaObject((JSON) o, FileInfo.class);

			System.out.println(fileInfo.getRelativePath());
		}


		//刷新本地group,加入到集群中

		return Restful.instance().msg("克隆成功");
	}
}
