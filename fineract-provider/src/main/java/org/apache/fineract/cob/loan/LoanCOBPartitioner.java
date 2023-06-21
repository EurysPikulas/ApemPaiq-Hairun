/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
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
package org.apache.fineract.cob.loan;

import com.google.common.collect.Lists;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.LongStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.cob.COBBusinessStepService;
import org.apache.fineract.cob.data.BusinessStepNameAndOrder;
import org.apache.fineract.cob.data.LoanCOBParameter;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.infrastructure.springbatch.PropertyService;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

@Slf4j
@RequiredArgsConstructor
public class LoanCOBPartitioner implements Partitioner {

    public static final String PARTITION_PREFIX = "partition_";

    private final PropertyService propertyService;
    private final COBBusinessStepService cobBusinessStepService;
    private final JobOperator jobOperator;
    private final JobExplorer jobExplorer;

    private final LoanCOBParameter minAndMaxLoanId;

    @NotNull
    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int partitionSize = propertyService.getPartitionSize(LoanCOBConstant.JOB_NAME);
        Set<BusinessStepNameAndOrder> cobBusinessSteps = cobBusinessStepService.getCOBBusinessSteps(LoanCOBBusinessStep.class,
                LoanCOBConstant.LOAN_COB_JOB_NAME);
        return getPartitions(partitionSize, cobBusinessSteps);
    }

    private Map<String, ExecutionContext> getPartitions(int partitionSize, Set<BusinessStepNameAndOrder> cobBusinessSteps) {
        Map<String, ExecutionContext> partitions = new HashMap<>();
        if (cobBusinessSteps.isEmpty()) {
            stopJobExecution();
            return Map.of();
        }
        if (!Objects.isNull(minAndMaxLoanId)) {
            List<Long> loanIdsInRange = LongStream.rangeClosed(minAndMaxLoanId.getMinLoanId(), minAndMaxLoanId.getMaxLoanId()).boxed()
                    .toList();
            List<List<Long>> loanIdPartitions = Lists.partition(loanIdsInRange, partitionSize);
            for (int i = 0; i < loanIdPartitions.size(); i++) {
                createNewPartition(partitions, i + 1, cobBusinessSteps, loanIdPartitions.get(i));
            }
        } else {
            createNewPartition(partitions, 1, cobBusinessSteps, List.of(0L));
        }
        return partitions;
    }

    private void createNewPartition(Map<String, ExecutionContext> partitions, int partitionIndex,
            Set<BusinessStepNameAndOrder> cobBusinessSteps, List<Long> loanIds) {
        ExecutionContext executionContext = new ExecutionContext();
        executionContext.put(LoanCOBConstant.BUSINESS_STEPS, cobBusinessSteps);
        executionContext.put(LoanCOBConstant.LOAN_COB_PARAMETER, new LoanCOBParameter(loanIds.get(0), loanIds.get(loanIds.size() - 1)));
        executionContext.put("partition", PARTITION_PREFIX + partitionIndex);
        partitions.put(PARTITION_PREFIX + partitionIndex, executionContext);
    }

    private void stopJobExecution() {
        Set<JobExecution> runningJobExecutions = jobExplorer.findRunningJobExecutions(JobName.LOAN_COB.name());
        for (JobExecution jobExecution : runningJobExecutions) {
            try {
                jobOperator.stop(jobExecution.getId());
            } catch (NoSuchJobExecutionException | JobExecutionNotRunningException e) {
                log.error("There is no running execution for the given execution ID. Execution ID: {}", jobExecution.getId());
                throw new RuntimeException(e);
            }
        }
    }
}
