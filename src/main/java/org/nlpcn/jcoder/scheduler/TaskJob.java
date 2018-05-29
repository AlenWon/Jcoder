package org.nlpcn.jcoder.scheduler;

import org.nlpcn.jcoder.domain.Task;
import org.nlpcn.jcoder.run.java.JavaRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskJob extends Thread {

	private static final Logger LOG = LoggerFactory.getLogger(TaskJob.class);

	private Task task = null;


	private long startTime = System.currentTimeMillis();

	/**
	 * 运行一个任务
	 *
	 * @param name
	 */
	public TaskJob(String name, Task task) {
		super(name);
		this.task = task;
	}

	public long getStartTime() {
		return startTime;
	}

	@Override
	public void run() {
		TaskRunManager.put(this.getName(),this);
		try {
			new JavaRunner(task).compile().instance().execute();
			task.updateSuccess();
		} catch (Exception e) {
			task.updateError();
			e.printStackTrace();
			LOG.error(e.getMessage(), e);
		} finally {
			TaskRunManager.remove(this.getName());
		}

	}

	public Task getTask() {
		return task;
	}


}
