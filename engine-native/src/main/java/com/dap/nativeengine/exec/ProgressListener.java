package com.dap.nativeengine.exec;

/** Receives node-level progress of one workflow run (job progress in /v1/jobs). */
@FunctionalInterface
public interface ProgressListener {
    /**
     * @param nodesTotal  number of nodes in the workflow snapshot
     * @param nodesDone   nodes that finished successfully
     * @param currentNode node that started or just finished; null between levels
     */
    void progress(int nodesTotal, int nodesDone, String currentNode);
}
