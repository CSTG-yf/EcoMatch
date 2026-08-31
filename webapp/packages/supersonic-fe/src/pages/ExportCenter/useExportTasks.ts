import { message } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { classifyExportError, isPollingStatus, upsertTask, validateExportRequest } from './model';
import { exportApi } from './service';
import { ExportCreateReq, ExportError, ExportTaskItem, ExportTaskResp } from './types';

export const useExportTasks = () => {
  const [tasks, setTasks] = useState<ExportTaskItem[]>([]);
  const [pageError, setPageError] = useState<ExportError>();
  const [creating, setCreating] = useState(false);
  const [loadingList, setLoadingList] = useState(false);
  const [activeTaskIds, setActiveTaskIds] = useState<string[]>([]);
  const tasksRef = useRef(tasks);
  const refreshingRef = useRef(new Set<string>());

  useEffect(() => {
    tasksRef.current = tasks;
  }, [tasks]);

  const setTask = useCallback((task: ExportTaskItem) => {
    setTasks((current) => upsertTask(current, task));
  }, []);

  const loadList = useCallback(async () => {
    setLoadingList(true);
    try {
      const page = await exportApi.list({ pageNum: 1, pageSize: 20 });
      setTasks((current) =>
        (page.list || []).map((task) => {
          const previous = current.find((item) => item.taskId === task.taskId);
          return {
            ...task,
            request: previous?.request,
            actionError: previous?.actionError,
          };
        }),
      );
      setPageError(undefined);
    } catch (error) {
      setPageError(classifyExportError(error));
    } finally {
      setLoadingList(false);
    }
  }, []);

  const withTaskAction = useCallback(
    async <T>(taskId: string, action: () => Promise<T>): Promise<T | undefined> => {
      setActiveTaskIds((current) => [...current, taskId]);
      try {
        const result = await action();
        setPageError(undefined);
        return result;
      } catch (error) {
        const actionError = classifyExportError(error);
        setPageError(actionError);
        const current = tasksRef.current.find((item) => item.taskId === taskId);
        if (current) setTask({ ...current, actionError });
        return undefined;
      } finally {
        setActiveTaskIds((current) => current.filter((id) => id !== taskId));
      }
    },
    [setTask],
  );

  const create = useCallback(
    async (request: ExportCreateReq): Promise<ExportTaskResp | undefined> => {
      setCreating(true);
      try {
        const normalized = validateExportRequest(request);
        const task = await exportApi.create(normalized);
        setTask({ ...task, request: normalized });
        setPageError(undefined);
        return task;
      } catch (error) {
        setPageError(classifyExportError(error));
        return undefined;
      } finally {
        setCreating(false);
      }
    },
    [setTask],
  );

  const refresh = useCallback(
    async (taskId: string) => {
      if (refreshingRef.current.has(taskId)) return undefined;
      refreshingRef.current.add(taskId);
      try {
        return await withTaskAction(taskId, async () => {
          const task = await exportApi.get(taskId);
          const previous = tasksRef.current.find((item) => item.taskId === taskId);
          setTask({ ...task, request: previous?.request });
          return task;
        });
      } finally {
        refreshingRef.current.delete(taskId);
      }
    },
    [setTask, withTaskAction],
  );

  const addByTaskId = useCallback(
    async (taskId: string) => {
      const normalized = taskId.trim();
      if (!normalized) return undefined;
      return withTaskAction(normalized, async () => {
        const task = await exportApi.get(normalized);
        setTask(task);
        return task;
      });
    },
    [setTask, withTaskAction],
  );

  const download = useCallback(
    async (task: ExportTaskItem) =>
      withTaskAction(task.taskId, async () => {
        const file = await exportApi.download(task.taskId, task.fileName);
        exportApi.saveDownload(file);
      }),
    [withTaskAction],
  );

  const remove = useCallback(
    async (task: ExportTaskItem) => {
      const deleted = await withTaskAction(task.taskId, async () => {
        await exportApi.remove(task.taskId);
        return true;
      });
      if (deleted) {
        setTasks((current) => current.filter((item) => item.taskId !== task.taskId));
        message.success('导出任务已删除');
      }
    },
    [withTaskAction],
  );

  const retry = useCallback(
    async (task: ExportTaskItem) => (task.request ? create(task.request) : undefined),
    [create],
  );

  useEffect(() => {
    const pollingTaskIds = tasks
      .filter((task) => isPollingStatus(task.status))
      .map((t) => t.taskId);
    if (!pollingTaskIds.length) return undefined;
    const timer = window.setInterval(() => {
      pollingTaskIds.forEach((taskId) => void refresh(taskId));
    }, 2_000);
    return () => window.clearInterval(timer);
  }, [refresh, tasks]);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  return {
    tasks,
    pageError,
    creating,
    loadingList,
    activeTaskIds,
    create,
    refresh,
    addByTaskId,
    download,
    remove,
    retry,
    loadList,
    clearError: () => setPageError(undefined),
  };
};
