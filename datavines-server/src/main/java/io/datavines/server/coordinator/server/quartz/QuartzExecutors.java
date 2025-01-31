/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.datavines.server.coordinator.server.quartz;

import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.Maps;

import io.datavines.server.DataVinesConstants;

/**
 * single Quartz executors instance
 */
public class QuartzExecutors {

  private static final Logger logger = LoggerFactory.getLogger(QuartzExecutors.class);

  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  private static final class Holder {
    private static final QuartzExecutors INSTANCE = new QuartzExecutors();
  }

  private QuartzExecutors() {
  }

  public static QuartzExecutors getInstance() {
    return Holder.INSTANCE;
  }


  /**
   * add task trigger , if this task already exists, return this task with updated trigger
   *
   * @param clazz             job class name
   * @param jobName           job name
   * @param jobGroupName      job group name
   * @param startDate         job start date
   * @param endDate           job end date
   * @param cronExpression    cron expression
   * @param jobDataMap        job parameters data map
   */
  public void addJob(Scheduler scheduler, Class<? extends Job> clazz,
                     String jobName,
                     String jobGroupName,
                     Date startDate,
                     Date endDate,
                     String cronExpression,
                     Map<String, Object> jobDataMap) {
    lock.writeLock().lock();
    try {

      JobKey jobKey = new JobKey(jobName, jobGroupName);
      JobDetail jobDetail;
      //add a task (if this task already exists, return this task directly)
      if (scheduler.checkExists(jobKey)) {

        jobDetail = scheduler.getJobDetail(jobKey);
        if (jobDataMap != null) {
          jobDetail.getJobDataMap().putAll(jobDataMap);
        }
      } else {
        jobDetail = newJob(clazz).withIdentity(jobKey).build();

        if (jobDataMap != null) {
          jobDetail.getJobDataMap().putAll(jobDataMap);
        }

        scheduler.addJob(jobDetail, false, true);

        logger.info("Add job, job name: {}, group name: {}",
                jobName, jobGroupName);
      }

      TriggerKey triggerKey = new TriggerKey(jobName, jobGroupName);

      /*
       * Instructs the Scheduler that upon a mis-fire
       * situation, the CronTrigger wants to have it's
       * next-fire-time updated to the next time in the schedule after the
       * current time (taking into account any associated Calendar),
       * but it does not want to be fired now.
       */
      CronTrigger cronTrigger = newTrigger().withIdentity(triggerKey).startAt(startDate).endAt(endDate)
              .withSchedule(cronSchedule(cronExpression).withMisfireHandlingInstructionDoNothing())
              .forJob(jobDetail).build();

      if (scheduler.checkExists(triggerKey)) {
          // updateProcessInstance scheduler trigger when scheduler cycle changes
          CronTrigger oldCronTrigger = (CronTrigger) scheduler.getTrigger(triggerKey);
          String oldCronExpression = oldCronTrigger.getCronExpression();

          if (!StringUtils.equalsIgnoreCase(cronExpression,oldCronExpression)) {
            // reschedule job trigger
            scheduler.rescheduleJob(triggerKey, cronTrigger);
            logger.info("reschedule job trigger, triggerName: {}, triggerGroupName: {}, cronExpression: {}, startDate: {}, endDate: {}",
                    jobName, jobGroupName, cronExpression, startDate, endDate);
          }
      } else {
        scheduler.scheduleJob(cronTrigger);
        logger.info("schedule job trigger, triggerName: {}, triggerGroupName: {}, cronExpression: {}, startDate: {}, endDate: {}",
                jobName, jobGroupName, cronExpression, startDate, endDate);
      }

    } catch (Exception e) {
      logger.error("add job failed", e);
      throw new RuntimeException("add job failed", e);
    } finally {
      lock.writeLock().unlock();
    }
  }


  /**
   * delete job
   *
   * @param jobName      job name
   * @param jobGroupName job group name
   * @return true if the Job was found and deleted.
   */
  public boolean deleteJob(Scheduler scheduler,String jobName, String jobGroupName) {
    lock.writeLock().lock();
    try {
      JobKey jobKey = new JobKey(jobName,jobGroupName);
      if(scheduler.checkExists(jobKey)){
        logger.info("try to delete job, job name: {}, job group name: {},", jobName, jobGroupName);
        return scheduler.deleteJob(jobKey);
      }else {
        return true;
      }

    } catch (SchedulerException e) {
      logger.error("delete job : {} failed",jobName, e);
    } finally {
      lock.writeLock().unlock();
    }
    return false;
  }

  /**
   * delete all jobs in job group
   *
   * @param jobGroupName job group name
   *
   * @return true if all of the Jobs were found and deleted, false if
   *      one or more were not deleted.
   */
  public boolean deleteAllJobs(Scheduler scheduler,String jobGroupName) {
    lock.writeLock().lock();
    try {
      logger.info("try to delete all jobs in job group: {}", jobGroupName);
      List<JobKey> jobKeys =
              new ArrayList<>(scheduler.getJobKeys(GroupMatcher.groupEndsWith(jobGroupName)));

      return scheduler.deleteJobs(jobKeys);
    } catch (SchedulerException e) {
      logger.error("delete all jobs in job group: {} failed",jobGroupName, e);
    } finally {
      lock.writeLock().unlock();
    }
    return false;
  }

  /**
   * build job name
   * @param scheduleId scheduleId
   * @return job name
   */
  public static String buildJobName(Long scheduleId) {
    return DataVinesConstants.QUARTZ_JOB_PREFIX + DataVinesConstants.UNDERLINE + scheduleId;
  }

  /**
   * build job group name
   * @param projectId project id
   * @return job group name
   */
  public static String buildJobGroupName(Long projectId) {
    return DataVinesConstants.QUARTZ_JOB_GROUP_PREFIX + DataVinesConstants.UNDERLINE + projectId;
  }

  /**
   * add params to map
   *
   * @param projectId   project id
   * @param scheduleId  schedule id
   * @return data map
   */
  public static Map<String, Object> buildDataMap(Long projectId, Long projectFlowId, int scheduleId) {
    Map<String, Object> dataMap = Maps.newHashMap();
    dataMap.put(DataVinesConstants.PROJECT_ID, projectId);
    dataMap.put(DataVinesConstants.PROJECT_JOB_ID, projectFlowId);
    dataMap.put(DataVinesConstants.SCHEDULE_ID, scheduleId);
    return dataMap;
  }

}
