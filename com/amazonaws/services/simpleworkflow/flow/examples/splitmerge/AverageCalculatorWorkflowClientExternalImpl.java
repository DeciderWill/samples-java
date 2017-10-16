/*
 * This code was generated by AWS Flow Framework Annotation Processor.
 * Refer to Amazon Simple Workflow Service documentation at http://aws.amazon.com/documentation/swf 
 *
 * Any changes made directly to this file will be lost when 
 * the code is regenerated.
 */
 package com.amazonaws.services.simpleworkflow.flow.examples.splitmerge;

import com.amazonaws.services.simpleworkflow.flow.DataConverter;
import com.amazonaws.services.simpleworkflow.flow.StartWorkflowOptions;
import com.amazonaws.services.simpleworkflow.flow.WorkflowClientExternalBase;
import com.amazonaws.services.simpleworkflow.flow.generic.GenericWorkflowClientExternal;
import com.amazonaws.services.simpleworkflow.model.WorkflowExecution;
import com.amazonaws.services.simpleworkflow.model.WorkflowType;

class AverageCalculatorWorkflowClientExternalImpl extends WorkflowClientExternalBase implements AverageCalculatorWorkflowClientExternal {

    public AverageCalculatorWorkflowClientExternalImpl(WorkflowExecution workflowExecution, WorkflowType workflowType, 
            StartWorkflowOptions options, DataConverter dataConverter, GenericWorkflowClientExternal genericClient) {
        super(workflowExecution, workflowType, options, dataConverter, genericClient);
    }

    @Override
    public void average(String bucketName, String fileName, int numberOfWorkers) { 
        average(bucketName, fileName, numberOfWorkers, null);
    }

    @Override
    public void average(String bucketName, String fileName, int numberOfWorkers, StartWorkflowOptions startOptionsOverride) {
    
        Object[] _arguments_ = new Object[3]; 
        _arguments_[0] = bucketName;
        _arguments_[1] = fileName;
        _arguments_[2] = numberOfWorkers;
        dynamicWorkflowClient.startWorkflowExecution(_arguments_, startOptionsOverride);
    }


}