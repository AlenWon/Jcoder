package org.nlpcn.jcoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import org.h2.util.DateTimeUtils;
import org.nlpcn.jcoder.constant.*;
import org.nlpcn.jcoder.domain.*;
import org.nlpcn.jcoder.filter.AuthoritiesManager;
import org.nlpcn.jcoder.run.CodeException;
import org.nlpcn.jcoder.run.java.JavaRunner;
import org.nlpcn.jcoder.scheduler.TaskException;
import org.nlpcn.jcoder.scheduler.TaskRunManager;
import org.nlpcn.jcoder.service.GroupService;
import org.nlpcn.jcoder.service.ProxyService;
import org.nlpcn.jcoder.service.TaskService;
import org.nlpcn.jcoder.util.*;
import org.nutz.http.Response;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Lang;
import org.nutz.mvc.Mvcs;
import org.nutz.mvc.adaptor.WhaleAdaptor;
import org.nutz.mvc.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.nlpcn.jcoder.constant.Constants.TIMEOUT;
import static org.nlpcn.jcoder.service.ProxyService.MERGE_FALSE_MESSAGE_CALLBACK;
import static org.nlpcn.jcoder.util.ApiException.*;

@IocBean
@Filters(@By(type = AuthoritiesManager.class))
@At("/admin/task")
@Ok("json")
public class TaskAction {

	private static final Logger LOG = LoggerFactory.getLogger(TaskAction.class);

	private static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

	@Inject
	private TaskService taskService;

	@Inject
	private ProxyService proxyService;

	@Inject
	private GroupService groupService;

	/**
	 * 获得task列表
	 *
	 * @param host      获取哪个主机的任务列表, 默认主版本(参数不传或为空)
	 * @param groupName 组名
	 * @param taskType  task类型: 0、垃圾；1、独立；2、计划；3、调度
	 */
	@At
	public Restful list(String host, String groupName, @Param(value = "taskType", df = "-1") int taskType) throws Exception {
		if (StringUtil.isBlank(groupName)) {
			throw new IllegalArgumentException("empty groupName");
		}

		if (Constants.HOST_MASTER.equals(host)) {
			host = null;
		}

		// 从主板本取任务列表
		if (StringUtil.isBlank(host)) { //先找一个机器。找不到再从主版本中获取
			host = groupService.getRandomCurrentHostPort(groupName);
		}

		if (StringUtil.isBlank(host)) {
			Object[] tasks = taskService.getTasksByGroupNameFromCluster(groupName)
					.stream()
					.filter(t -> -1 == taskType || Objects.equals(t.getType(), taskType))
					.map(t -> Maps.hash("name", t.getName(),
							"groupName", t.getGroupName(),
							"hostPort", "master",
							"description", Optional.ofNullable(t.getDescription()).orElse(StringUtil.EMPTY),
							"status", t.getStatus(),
							"createTime", DateTimeUtils.formatDateTime(t.getCreateTime(), DATETIME_FORMAT, null, null),
							"updateTime", DateTimeUtils.formatDateTime(t.getUpdateTime(), DATETIME_FORMAT, null, null),
							"compile", t.sourceUtil() != null))
					.toArray();
			return Restful.instance(tasks);
		}

		Response res = proxyService.post(host, Api.TASK_LIST.getPath(), ImmutableMap.of("groupName", groupName, "taskType", taskType), Constants.TIMEOUT);

		Restful restful = Restful.instance(res);

		if (!restful.isOk()) {
			return Restful.fail().code(ServerException).msg(restful.getMessage());
		}

		return restful;
	}

	@At
	public Restful __list__(String groupName, int taskType) {

		//判斷本機是否衝突狀態
		boolean isCurrent = groupService.getCurrentHostPort(groupName).stream().collect(Collectors.toSet()).contains(StaticValue.getHostPort());

		Object[] tasks = taskService.findTaskByGroupNameCache(groupName)
				.stream()
				.filter(t -> -1 == taskType || Objects.equals(t.getType(), taskType))
				.map(t -> {

					boolean compile = false;
					if (t.getStatus() == 1) { //停用状态不需要编译，只要代码规范即可
						compile = t.sourceUtil() != null && t.codeInfo().getClassz() != null;
					} else {
						compile = t.sourceUtil() != null;
					}

					Map<String, Object> map = Maps.hash("name", t.getName(),
							"groupName", t.getGroupName(),
							"hostPort", StaticValue.getHostPort(),
							"description", Optional.ofNullable(t.getDescription()).orElse(StringUtil.EMPTY),
							"status", t.getStatus(),
							"createTime", DateTimeUtils.formatDateTime(t.getCreateTime(), DATETIME_FORMAT, null, null),
							"updateTime", DateTimeUtils.formatDateTime(t.getUpdateTime(), DATETIME_FORMAT, null, null),
							"compile", compile);

					if (!isCurrent) {
						Different different = new Different();
						StaticValue.space().diffTask(t, different, groupName, t.getName(), false); //查找冲突原因
						if (StringUtil.isNotBlank(different.getMessage())) {
							map.put("diff", different.getMessage());
						}
					}
					return map;
				})
				.toArray();


		return Restful.instance(tasks);
	}

	/**
	 * 获得任务对应的主机列表
	 *
	 * @param groupName 组名
	 * @param name      任务名
	 */
	@At("/host/list")
	public Restful hostList(String groupName, String name) throws Exception {
		if (StringUtil.isBlank(groupName)) {
			throw new IllegalArgumentException("empty groupName");
		}

		if (StringUtil.isBlank(name)) {
			throw new IllegalArgumentException("empty task name");
		}

		// 获取有这个组的所有主机
		List<String> hosts = groupService.getGroupHostList(groupName).stream().map(HostGroup::getHostPort).collect(Collectors.toList());

		// 询问主机是否有这个任务
		hosts = hosts.parallelStream().filter(h -> {
			String content;
			try {
				content = proxyService.post(h, Api.TASK_TASK.getPath(), ImmutableMap.of("groupName", groupName, "name", name), TIMEOUT).getContent();
			} catch (Exception e) {
				throw Lang.wrapThrow(e);
			}
			return JSON.parseObject(content).get("obj") != null;
		}).collect(Collectors.toList());

		//
		if (taskService.getTasksByGroupNameFromCluster(groupName).stream().anyMatch(t -> Objects.equals(t.getName(), name))) {
			hosts.add(0, Constants.HOST_MASTER);
		}

		return Restful.instance(hosts);
	}

	/**
	 * 保存或更新任务
	 *
	 * @param oldName 更新之前的名字
	 */
	@At
	public Restful save(@Param("hosts[]") String[] hosts, @Param("task") Task task, @Param(value = "oldName", df = StringUtil.EMPTY) String oldName) throws Exception {
		if (hosts == null || hosts.length < 1) {
			throw new IllegalArgumentException("empty hosts");
		}

		if (task == null) {
			throw new IllegalArgumentException("task is null");
		}

		if (StringUtil.isBlank(task.getName()) || StringUtil.isBlank(task.getCode())) {
			throw new IllegalArgumentException("task name or code is empty");
		}

		// 是否更新主版本
		Set<String> hostPorts = Stream.of(hosts).collect(Collectors.toSet());
		boolean containsMaster = hostPorts.remove(Constants.HOST_MASTER);

		if (StringUtil.isNotBlank(oldName) && !oldName.equals(task.getName()) && taskService.existsInCluster(task.getGroupName(), oldName) && taskService.existsInCluster(task.getGroupName(), task.getName())) { //名字变了，新名字旧名字集群都存在，那么可能错误的添加。集群中存在
			return Restful.fail().code(Forbidden).msg("task[" + task.getGroupName() + "-" + task.getName() + "] already exists in [master]");
		}

		// 如果是新增, 检查主版本上是否已存在
		if (containsMaster && task.getId() == null && taskService.existsInCluster(task.getGroupName(), task.getName())) {
			return Restful.fail().code(Forbidden).msg("task[" + task.getGroupName() + "-" + task.getName() + "] already exists in [master]");
		}
		// 如果激活任务, 需要检查代码
		// 如果是新增, 确保任务不存在
		Restful restful = proxyService.post(hostPorts, Api.TASK_CHECK.getPath(), ImmutableMap.of("task", JSON.toJSONString(task)), TIMEOUT, MERGE_FALSE_MESSAGE_CALLBACK);
		if (!restful.isOk()) {
			return restful;
		}

		// 保存
		User u = (User) Mvcs.getHttpSession().getAttribute(UserConstants.USER);
		Date now = new Date();
		if (task.getId() == null) {
			task.setCreateUser(u.getName());
			task.setCreateTime(now);
		}
		task.setUpdateUser(u.getName());
		task.setUpdateTime(now);
		String taskStr = JSON.toJSONString(task);


		// 集群的每台机器保存
		restful = proxyService.post(hostPorts, Api.TASK_SAVE.getPath(), ImmutableMap.of("task", taskStr, "oldName", oldName), TIMEOUT, MERGE_FALSE_MESSAGE_CALLBACK);

		// 如果更新主版本
		if (containsMaster) {
			// 如果任务名变更, 需要删除之前的任务
			if (StringUtil.isNotBlank(oldName) && !oldName.equals(task.getName())) {
				taskService.deleteTaskFromCluster(task.getGroupName(), oldName);

				// 重新创建一个任务
				task.setCreateUser(task.getUpdateUser());
				task.setCreateTime(task.getUpdateTime());
			}

			// 给任务设置ID值, 方便后续做编辑
			task.setId(Constants.MASTER_TASK_ID);

			// ZK保存
			StaticValue.space().addTask(task);
		}


		// 不管有没有更新主版本。所有机器都做diff
		Set<String> hostPortSet = groupService.getGroupHostList(task.getGroupName()).stream().map(HostGroup::getHostPort).collect(Collectors.toSet());

		// diff
		restful = proxyService.post(hostPortSet, Api.TASK_DIFF.getPath(), ImmutableMap.of("groupName", task.getGroupName(), "taskName", task.getName(), "oldName", oldName), TIMEOUT, MERGE_FALSE_MESSAGE_CALLBACK);
		if (!restful.isOk()) {
			return restful;
		}

		if (!restful.isOk()) {
			return restful;
		}

		return Restful.ok().obj(task.getName());
	}

	@At
	public Restful __check__(@Param("::task") Task task) throws CodeException, IOException {
		// 如果是新增, 确保任务不存在
		if (task.getId() == null && taskService.findTask(task.getGroupName(), task.getName()) != null) {
			return Restful.fail().code(Forbidden).msg(String.format("task[%s-%s] already exists in [%s]", task.getGroupName(), task.getName(), StaticValue.getHostPort()));
		}

		// 如果激活任务, 需要检查代码
		if (task.getStatus() == TaskStatus.ACTIVE.getValue()) {
			new JavaRunner(task).check();
		}

		return Restful.ok();
	}

	@At
	public Restful __save__(@Param("::task") Task task, String oldName) throws Exception {
		boolean nameChanged = StringUtil.isNotBlank(oldName) && !oldName.equals(task.getName());

		if (!oldName.equals(task.getName()) && taskService.findTask(task.getGroupName(), oldName) != null && taskService.findTask(task.getGroupName(), task.getName()) != null) { //名字变了，新名字旧名字集群都存在，那么可能错误的添加。集群中存在
			return Restful.fail().code(Forbidden).msg("task[" + task.getGroupName() + "-" + task.getName() + "] already exists in [master]");
		}

		// 如果任务名变更, 强制删除之前的任务, 但不做diff
		if (nameChanged) {
			__delete__(false, true, task.getGroupName(), oldName, StringUtil.EMPTY, new Date());
		}

		// 如果是编辑
		if (task.getId() != null) {
			Task t;
			if (!nameChanged && (t = taskService.findTask(task.getGroupName(), task.getName())) != null) {
				// 如果更新的是本地任务, 替换成本地任务ID
				task.setId(t.getId());
			} else {
				// 重新创建一个任务
				task.setId(null);
				task.setCreateUser(task.getUpdateUser());
				task.setCreateTime(task.getUpdateTime());
			}
		}
		LOG.info("to save task[{}-{}]: {}", task.getGroupName(), task.getName(), taskService.saveOrUpdate(task));

		if (StaticValue.TESTRING) { //测试模式下写入
			try {
				GroupFileListener.writeTask2Src(task);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return Restful.ok();
	}

	@At
	public Restful __diff__(String groupName, String taskName, String oldName) throws Exception {

		LOG.info("host[{}] begin to diff[group={}, taskName={}, oldName={}] with master", StaticValue.getHostPort(), groupName, taskName, oldName);

		Set<String> taskNames = new HashSet<>(2);
		taskNames.add(taskName);
		if (StringUtil.isNotBlank(oldName)) {
			taskNames.add(oldName);
		}
		StaticValue.space().different(groupName, taskNames, null, false, false);

		return Restful.ok();
	}

	/**
	 * 获取任务
	 *
	 * @param groupName  组名
	 * @param name       任务名
	 * @param sourceHost 来源主机, 即获取哪个主机的任务, 默认主版本
	 */
	@At
	@AdaptBy(type = WhaleAdaptor.class)
	public Restful task(String groupName, String name, @Param(value = "sourceHost", df = Constants.HOST_MASTER) String sourceHost) throws Exception {
		if (StringUtil.isBlank(groupName)) {
			throw new IllegalArgumentException("empty groupName");
		}

		if (StringUtil.isBlank(name)) {
			throw new IllegalArgumentException("empty name");
		}

		// 如果取主版本, 直接访问ZK
		if (Constants.HOST_MASTER.equals(sourceHost)) {
			Task task = taskService.getTaskFromCluster(groupName, name);
			if (task == null) {
				LOG.warn("task[{}-{}] not found in zookeeper", groupName, name);
				return Restful.ok();
			}
			return Restful.ok().obj(task);
		} else if (StaticValue.getHostPort().equals(sourceHost)) {
			return __task__(groupName, name);
		}

		// 如果取其他机器版本
		Response resp = proxyService.post(sourceHost, Api.TASK_TASK.getPath(), ImmutableMap.of("groupName", groupName, "name", name), TIMEOUT);

		return Restful.instance(resp);
	}

	@At
	public Restful __task__(String groupName, String name) {
		// 从本地数据库中找到任务
		Task t = taskService.findTask(groupName, name);
		if (t == null) {
			LOG.warn("task[{}-{}] not found in host[{}]", groupName, name, StaticValue.getHostPort());
			return Restful.fail().code(NotFound);
		}

		return Restful.ok().obj(t);
	}

	/**
	 * 获取任务所有的版本信息
	 *
	 * @param host      主机名
	 * @param groupName 组名
	 * @param name      任务名
	 * @param size      版本的数量限制
	 */
	@At
	public Restful version(String host, String groupName, String name, @Param(value = "size", df = "50") int size) throws Exception {
		if (StringUtil.isBlank(host)) {
			throw new IllegalArgumentException("empty host");
		}
		if (StringUtil.isBlank(groupName)) {
			throw new IllegalArgumentException("empty groupName");
		}
		if (StringUtil.isBlank(name)) {
			throw new IllegalArgumentException("empty name");
		}

		// 如果取主版本, 直接访问ZK
		if (Constants.HOST_MASTER.equals(host)) {
			LOG.warn("only one task[{}-{}] in zookeeper", groupName, name);
			return Restful.ok();
		} else if (StaticValue.getHostPort().equals(host)) {
			return __version__(groupName, name, size);
		}

		//
		Response resp = proxyService.post(host, Api.TASK_VERSION.getPath(), ImmutableMap.of("groupName", groupName, "name", name, "size", size), TIMEOUT);
		return Restful.instance(resp);
	}

	@At
	public Restful __version__(String groupName, String name, int size) {
		// 从本地数据库中找到任务
		Task t = taskService.findTask(groupName, name);
		if (t == null) {
			LOG.warn("task[{}-{}] not found in host[{}]", groupName, name, StaticValue.getHostPort());
			return Restful.fail().code(NotFound);
		}
		return Restful.ok().obj(taskService.versions(t.getId(), size));
	}

	/**
	 * 获取历史版本的任务
	 *
	 * @param host      主机名
	 * @param groupName 组名
	 * @param name      任务名
	 * @param version   版本
	 */
	@At
	public Restful history(String host, String groupName, String name, String version) throws Exception {
		if (StringUtil.isBlank(host)) {
			throw new IllegalArgumentException("empty host");
		}
		if (StringUtil.isBlank(groupName)) {
			throw new IllegalArgumentException("empty groupName");
		}
		if (StringUtil.isBlank(name)) {
			throw new IllegalArgumentException("empty name");
		}

		// 如果取主版本, 直接访问ZK
		if (Constants.HOST_MASTER.equals(host)) {
			Optional<Task> opt = taskService.getTasksByGroupNameFromCluster(groupName).stream().filter(t -> Objects.equals(t.getName(), name)).findAny();
			if (!opt.isPresent()) {
				LOG.warn("task[{}-{}] not found in zookeeper", groupName, name);
				return Restful.fail().code(NotFound).msg("task not found in master");
			}

			return Restful.ok().obj(opt.get().getCode());
		} else {
			if (StringUtil.isBlank(version)) {
				throw new IllegalArgumentException("empty version");
			}
		}

		if (StaticValue.getHostPort().equals(host)) {
			return __history__(groupName, name, version);
		}

		//
		Response resp = proxyService.post(host, Api.TASK_HISTORY.getPath(), ImmutableMap.of("groupName", groupName, "name", name, "version", version), TIMEOUT);
		return Restful.instance(resp);
	}

	@At
	public Restful __history__(String groupName, String name, String version) {
		// 从本地数据库中找到任务
		Task t = taskService.findTask(groupName, name);
		if (t == null) {
			LOG.warn("task[{}-{}] not found in host[{}]", groupName, name, StaticValue.getHostPort());
			return Restful.fail().code(NotFound);
		}
		return Restful.ok().obj(TaskService.findTaskByDBHistory(t.getId(), version).getCode());
	}

	/**
	 * 删除任务
	 * 如果任务类型是垃圾, 就物理删除
	 * 如果任务类型不是垃圾, 就更改任务类型和状态
	 */
	@At
	public Restful delete(@Param("hosts[]") String[] hosts, String groupName, String name, int type) throws Exception {
		if (hosts == null || hosts.length < 1) {
			throw new IllegalArgumentException("empty hosts");
		}

		if (StringUtil.isBlank(groupName)) {
			throw new IllegalArgumentException("empty groupName");
		}

		if (StringUtil.isBlank(name)) {
			throw new IllegalArgumentException("empty name");
		}

		// 是否删除主版本
		Set<String> hostPorts = Stream.of(hosts).collect(Collectors.toSet());
		boolean containsMaster = hostPorts.remove(Constants.HOST_MASTER);
		if (hostPorts.isEmpty()) {
			throw new IllegalArgumentException("must contain non-master host");
		}

		User u = (User) Mvcs.getHttpSession().getAttribute(UserConstants.USER);
		Date now = new Date();

		if (type == TaskType.RECYCLE.getValue()) {
			// 如果是垃圾, 物理删除
			if (containsMaster) {
				// 删ZK
				taskService.deleteTaskFromCluster(groupName, name);
			}

			// 集群的每台机器删除
			Restful restful = proxyService.post(hostPorts, Api.TASK_DELETE.getPath(), ImmutableMap.of("force", true, "groupName", groupName, "name", name, "user", u.getName(), "time", now.getTime()), TIMEOUT, MERGE_FALSE_MESSAGE_CALLBACK);
			if (!restful.isOk()) {
				return restful;
			}
		} else {
			// 更改类型为垃圾, 并停用
			if (containsMaster) {
				// 更新ZK
				Task task = taskService.getTasksByGroupNameFromCluster(groupName).parallelStream().filter(t -> Objects.equals(t.getName(), name)).findAny().get();
				task.setUpdateUser(u.getName());
				task.setUpdateTime(now);
				task.setType(TaskType.RECYCLE.getValue());
				task.setStatus(TaskStatus.STOP.getValue());
				StaticValue.space().addTask(task);
			}

			// 集群的每台机器更新
			Restful restful = proxyService.post(hostPorts, Api.TASK_DELETE.getPath(), ImmutableMap.of("groupName", groupName, "name", name, "user", u.getName(), "time", now.getTime()), TIMEOUT, MERGE_FALSE_MESSAGE_CALLBACK);
			if (!restful.isOk()) {
				return restful;
			}
		}

		// 如果更新了主版本, 所有机器都得做diff
		if (containsMaster) {
			// 首先排除这次指定更新的主机
			Set<String> hostPortSet = groupService.getGroupHostList(groupName).stream().map(HostGroup::getHostPort).collect(Collectors.toSet());
			hostPortSet.removeAll(hostPorts);

			// diff
			Restful restful = proxyService.post(hostPortSet, Api.TASK_DIFF.getPath(), ImmutableMap.of("groupName", groupName, "taskName", name, "oldName", name), TIMEOUT, MERGE_FALSE_MESSAGE_CALLBACK);
			if (!restful.isOk()) {
				return restful.code(ServerException);
			}
		}

		return Restful.ok();
	}

	/**
	 * 内部调用删除任务
	 *
	 * @param diff      删除任务后是否与主版本diff, 默认是true
	 * @param force     是否强制删除任务, 如果是就删除数据库的任务
	 * @param groupName 组名
	 * @param name      任务名
	 * @param user      删除这个任务的用户
	 * @param time      删除这个任务的时间
	 */
	@At
	public Restful __delete__(@Param(value = "diff", df = "true") boolean diff, @Param(value = "force", df = "false") boolean force, String groupName, String name, String user, Date time) throws Exception {

		LOG.info("to force[{}] delete task[{}-{}]", force, groupName, name);

		// 从本地数据库中找到任务
		Task t = taskService.findTask(groupName, name);
		if (t == null) {
			LOG.warn("task[{}-{}] not found in host[{}]", groupName, name, StaticValue.getHostPort());
			return Restful.ok();
		}

		t.setUpdateUser(user);
		t.setUpdateTime(time);
		taskService.delete(t);

		// 如果强制删除, 删除数据库中任务
		if (force) {
			taskService.delByDB(t);
		}

		// diff
		if (diff) {
			__diff__(groupName, name, name);
		}

		return Restful.ok();
	}

	/**
	 * 通过一个接口获取这个group下的所有task，包括未激活或者删除的task，镜像用
	 */
	@At
	public Restful taskGroupList(@Param("groupName") String groupName) {
		return Restful.instance(taskService.findTasksByGroupName(groupName));
	}

	/**
	 * 传入多个主机获取主机们的成功失败数字
	 */
	@At
	public Restful statistics(@Param("groupName") String groupName, @Param(value = "name", df = "") String name, @Param(value = "first", df = "true") boolean first) throws Exception {
		if (!first) {
			long success = 0;
			long error = 0;
			if (StringUtil.isNotBlank(name)) {
				Task taskByCache = TaskService.findTaskByCache(groupName, name);
				if (taskByCache != null) {
					success = taskByCache.success();
					error = taskByCache.error();
				}
			}

			return Restful.ok().msg(success + "_" + error);
		} else {

			List<HostGroup> groupHostList = groupService.getGroupHostList(groupName);

			String[] hostPorts = groupHostList.stream().map(HostGroup::getHostPort).toArray(String[]::new);

			Map<String, Restful> map = proxyService.post(hostPorts, "/admin/task/statistics", ImmutableMap.of("groupName", groupName, "name", name, "first", false), 10000);

			List<TaskStatistics> result = new ArrayList<>();

			for (HostGroup hostGroup : groupHostList) {
				Restful restful = map.get(hostGroup.getHostPort());

				if (!restful.isOk()) {
					continue;
				}

				String[] split = restful.getMessage().split("_");

				TaskStatistics ts = new TaskStatistics();
				ts.setHostPort(hostGroup.getHostPort());
				ts.setSuccess(Long.parseLong(split[0]));
				ts.setError(Long.parseLong(split[1]));
				ts.setWeight(hostGroup.getWeight());
				ts.setHostGroup(hostGroup);
				result.add(ts);
			}

			final long sum = result.stream().mapToLong(TaskStatistics::getWeight).sum();
			result.forEach(t -> t.setSumWeight(sum));

			TaskStatistics master = new TaskStatistics();
			master.setSumWeight(sum);
			master.setWeight(sum);
			master.setError(result.stream().mapToLong(TaskStatistics::getError).sum());
			master.setSuccess(result.stream().mapToLong(TaskStatistics::getSuccess).sum());
			master.setHostPort("master");

			result.add(0, master);

			return Restful.ok().obj(result);
		}
	}

	/**
	 * 接收定时任务的接口，无法通过外部api调用
	 */
	@At
	public Restful __cron__(@Param("groupName") String groupName, @Param("taskName") String taskName) {
		User user = (User) Mvcs.getHttpSession(false).getAttribute(UserConstants.USER);
		if (user.getId() != -1) {
			return Restful.fail().code(ApiException.TokenNoPermissions).msg("your account not support this api");
		}
		Task task = TaskService.findTaskByCache(groupName, taskName);
		if (task == null) {
			return Restful.fail().code(NotFound).msg(taskName + " not found ");
		}

		if (task.getStatus() == 0) {
			return Restful.fail().code(ApiException.Forbidden).msg("task " + taskName + " status is 0 so skip !");
		}

		try {
			TaskRunManager.run(task);
		} catch (TaskException e) {
			e.printStackTrace();
			return Restful.fail().code(ApiException.ServerException).msg("task run fail " + taskName + e.getMessage());
		}

		return Restful.ok();
	}


	/**
	 * 同步task到本机，只进行可抽取验证，只要有类名称和package名称就存储，这个存储包括删除
	 */
	@At
	public Restful __syn__(@Param("fromHost") String fromHost, @Param("groupName") String groupName, @Param("taskName") String taskName) throws Exception {
		User user = (User) Mvcs.getHttpSession(false).getAttribute(UserConstants.USER);
		if (user.getId() != -1) {
			return Restful.fail().code(ApiException.TokenNoPermissions).msg("your account not support this api");
		}

		Restful restful = task(groupName, taskName, fromHost);

		if (restful.code() == 404) { //删除
			return __delete__(false, true, groupName, taskName, user.getName(), new Date());
		} else if (restful.code() == 200 && restful.getObj() != null) {
			Task remote = null;
			if (restful.getObj() instanceof Task) {
				remote = restful.getObj();
			} else {
				remote = JSONObject.toJavaObject(restful.getObj(), Task.class);
			}

			//从本机查一下要是有。本机走update，没有走save
			Task local = taskService.findTask(groupName, taskName);
			String oldName = null;
			if (local != null) {
				oldName = local.getName();
				remote.setId(local.getId());
			} else {
				remote.setId(null);
			}
			return __save__(remote, oldName);
		} else {
			return restful;
		}

	}
}
