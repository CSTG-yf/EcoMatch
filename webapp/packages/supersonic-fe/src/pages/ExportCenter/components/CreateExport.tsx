import { DownloadOutlined, InfoCircleOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, InputNumber, Modal, Radio, Space, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  buildExportRequest,
  buildLockedExportRequest,
  dataRangeText,
  parseQueryInput,
} from '../model';
import { CreateExportProps, ExportFormat, ExportResourceType } from '../types';
import styles from '../style.less';

interface FormValues {
  resourceType: ExportResourceType;
  format: ExportFormat;
  title?: string;
  dashboardId?: number;
  queryJson: string;
}

const requestToValues = (initialRequest?: CreateExportProps['initialRequest']): FormValues => {
  const resourceType = initialRequest?.resourceType || 'QUERY';
  const queries = initialRequest?.queries || [];
  return {
    resourceType,
    format: initialRequest?.format || 'XLSX',
    title: initialRequest?.title,
    dashboardId: initialRequest?.dashboardId,
    queryJson: JSON.stringify(resourceType === 'QUERY' ? queries[0] || {} : queries, null, 2),
  };
};

const CreateExport = ({
  initialRequest,
  lockedSource = false,
  autoOpen = false,
  disabled = false,
  buttonType = 'primary',
  buttonText = '创建导出',
  onCreate,
}: CreateExportProps) => {
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState(initialRequest);
  const [submitError, setSubmitError] = useState('');
  const [form] = Form.useForm<FormValues>();
  const resourceType =
    Form.useWatch('resourceType', form) || initialRequest?.resourceType || 'QUERY';
  const format = Form.useWatch('format', form) || initialRequest?.format || 'XLSX';

  const initialValues = useMemo(() => requestToValues(initialRequest), [initialRequest]);

  useEffect(() => {
    if (autoOpen) {
      setOpen(true);
    }
  }, [autoOpen]);

  useEffect(() => {
    if (open) {
      form.setFieldsValue(initialValues);
      setPreview(initialRequest);
      setSubmitError('');
    }
  }, [form, initialRequest, initialValues, open]);

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const request = lockedSource
        ? buildLockedExportRequest(initialRequest, values.format)
        : buildExportRequest({
            resourceType: values.resourceType,
            format: values.format,
            title: values.title,
            dashboardId: values.dashboardId,
            queries: parseQueryInput(values.queryJson, values.resourceType),
            charts: initialRequest?.charts || [],
          });
      setPreview(request);
      setSubmitError('');
      const task = await onCreate(request);
      if (task) setOpen(false);
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : '无法创建导出任务');
    } finally {
      setSubmitting(false);
    }
  };

  const updatePreview = () => {
    const values = form.getFieldsValue();
    if (lockedSource) {
      setPreview(initialRequest ? { ...initialRequest, format: values.format } : undefined);
      return;
    }
    try {
      setPreview(
        buildExportRequest({
          resourceType: values.resourceType,
          format: values.format,
          title: values.title,
          dashboardId: values.dashboardId,
          queries: parseQueryInput(values.queryJson, values.resourceType),
          charts: initialRequest?.charts || [],
        }),
      );
    } catch {
      setPreview(undefined);
    }
  };

  return (
    <>
      <Button
        type={buttonType}
        icon={<DownloadOutlined />}
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        {buttonText}
      </Button>
      <Modal
        title="创建受控导出"
        open={open}
        width={680}
        okText="创建任务"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => void submit()}
        onCancel={() => !submitting && setOpen(false)}
        destroyOnClose
      >
        <Form<FormValues>
          form={form}
          layout="vertical"
          initialValues={initialValues}
          onValuesChange={updatePreview}
          className={styles.createForm}
        >
          {submitError && (
            <Alert className={styles.formAlert} type="error" showIcon message={submitError} />
          )}
          <div className={lockedSource ? undefined : styles.formGrid}>
            {!lockedSource && (
              <Form.Item label="数据来源" name="resourceType" rules={[{ required: true }]}>
                <Radio.Group buttonStyle="solid">
                  <Radio.Button value="QUERY">问数结果</Radio.Button>
                  <Radio.Button value="DASHBOARD">分析看板</Radio.Button>
                </Radio.Group>
              </Form.Item>
            )}
            <Form.Item label="文件格式" name="format" rules={[{ required: true }]}>
              <Radio.Group buttonStyle="solid">
                <Radio.Button value="XLSX">XLSX</Radio.Button>
                <Radio.Button value="PDF">PDF</Radio.Button>
              </Radio.Group>
            </Form.Item>
          </div>
          {lockedSource ? (
            <div className={styles.lockedSourceSummary}>
              <Typography.Text type="secondary">导出来源</Typography.Text>
              <Typography.Text strong>
                {initialRequest?.title ||
                  (initialRequest?.resourceType === 'DASHBOARD' ? '分析看板' : '问数结果')}
              </Typography.Text>
              <Typography.Text type="secondary">
                {initialRequest ? dataRangeText({ ...initialRequest, format }) : '未提供可信来源'}
              </Typography.Text>
            </div>
          ) : (
            <Form.Item
              label="导出标题"
              name="title"
              rules={[{ max: 200, message: '标题不能超过 200 个字符' }]}
            >
              <Input placeholder="例如：本月存款余额分析" maxLength={200} showCount />
            </Form.Item>
          )}
          {!lockedSource && resourceType === 'DASHBOARD' && (
            <Form.Item
              label="看板 ID"
              name="dashboardId"
              rules={[{ required: true, message: '请输入看板 ID' }]}
            >
              <InputNumber min={1} precision={0} placeholder="当前用户有权访问的看板 ID" />
            </Form.Item>
          )}
          {!lockedSource && (
            <Form.Item
              label={resourceType === 'QUERY' ? '结构化查询' : '看板结构化查询数组'}
              name="queryJson"
              extra="仅接受语义层 QueryStructReq，不接受原始 SQL 或结果快照。"
              rules={[{ required: true, message: '请输入结构化查询 JSON' }]}
            >
              <Input.TextArea
                className={styles.queryEditor}
                autoSize={{ minRows: 7, maxRows: 14 }}
                spellCheck={false}
                placeholder={resourceType === 'QUERY' ? '{ "dataSetId": 1, ... }' : '[{ ... }]'}
              />
            </Form.Item>
          )}
          <Alert
            type="info"
            showIcon
            icon={<InfoCircleOutlined />}
            message="权限和脱敏范围"
            description={
              <Space direction="vertical" size={2}>
                <Typography.Text>
                  服务端会按当前登录身份重新执行查询，应用行列权限和字段脱敏；浏览器不会上传结果快照。
                </Typography.Text>
                <Typography.Text type="secondary">
                  {preview
                    ? dataRangeText({ ...preview, format })
                    : 'XLSX 最多 10,000 行，PDF 最多 500 行，文件最大 25 MB，成功文件保留 24 小时。'}
                </Typography.Text>
              </Space>
            }
          />
        </Form>
      </Modal>
    </>
  );
};

export default CreateExport;
