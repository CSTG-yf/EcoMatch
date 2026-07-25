import {
  CheckCircleOutlined,
  FileExcelOutlined,
  InboxOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  message,
  Result,
  Space,
  Steps,
  Table,
  Tag,
  Upload,
} from 'antd';
import type { UploadProps } from 'antd';
import React, { useEffect, useState } from 'react';
import { importBankWorkbook, validateBankWorkbook } from './service';
import type { ImportReport } from './types';
import styles from './style.less';

const { Dragger } = Upload;

interface Props {
  modelId?: number;
  onImported: () => void;
}

const responseData = (response: any): any => {
  if (response?.code !== undefined) {
    if (response.code !== 200) {
      throw new Error(response.msg || '请求失败');
    }
    return response.data;
  }
  return response;
};

const counterText = (counter?: Record<string, number>) =>
  Object.entries(counter || {})
    .map(([key, value]) => `${key} ${value}`)
    .join('，') || '-';

const ImportPanel: React.FC<Props> = ({ modelId, onImported }) => {
  const [form] = Form.useForm();
  const [file, setFile] = useState<File>();
  const [report, setReport] = useState<ImportReport>();
  const [validating, setValidating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [completed, setCompleted] = useState(false);

  useEffect(() => {
    form.setFieldValue('modelId', modelId);
  }, [form, modelId]);

  const uploadProps: UploadProps = {
    accept: '.xlsx,.xls',
    maxCount: 1,
    showUploadList: true,
    beforeUpload: (nextFile) => {
      setFile(nextFile as File);
      setReport(undefined);
      setCompleted(false);
      return false;
    },
    onRemove: () => {
      setFile(undefined);
      setReport(undefined);
      setCompleted(false);
    },
  };

  const validate = async () => {
    if (!file) return;
    setValidating(true);
    try {
      const nextReport = responseData(await validateBankWorkbook(file));
      setReport(nextReport);
      if (nextReport?.success) message.success('工作簿校验通过');
    } catch (error: any) {
      message.error(error.message || '工作簿校验失败');
    } finally {
      setValidating(false);
    }
  };

  const runImport = async () => {
    if (!file || !report?.success || !modelId) return;
    const values = await form.validateFields();
    setImporting(true);
    try {
      const nextReport = responseData(await importBankWorkbook(file, values));
      setReport(nextReport);
      setCompleted(Boolean(nextReport?.success));
      if (nextReport?.success) {
        message.success('指标与术语导入完成');
        onImported();
      } else {
        message.error('导入失败，请查看错误明细');
      }
    } catch (error: any) {
      message.error(error.message || '导入失败');
    } finally {
      setImporting(false);
    }
  };

  const current = completed ? 3 : report?.success ? 2 : file ? 1 : 0;

  return (
    <div className={styles.importPanel}>
      <Steps
        current={current}
        size="small"
        items={[
          { title: '选择文件' },
          { title: '数据校验' },
          { title: '导入配置' },
          { title: '完成' },
        ]}
      />

      <div className={styles.importBody}>
        <Dragger {...uploadProps} disabled={validating || importing}>
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">选择银行指标与术语工作簿</p>
          <p className="ant-upload-hint">支持比赛标准 Excel 模板，上传后先执行只读校验</p>
        </Dragger>
        <Space>
          <Button
            icon={<FileExcelOutlined />}
            disabled={!file}
            loading={validating}
            onClick={validate}
          >
            校验工作簿
          </Button>
          {report?.success && !completed && <Tag color="success">校验通过</Tag>}
        </Space>

        {report && (
          <>
            <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 4 }} bordered>
              <Descriptions.Item label="机构">{report.organizationCount}</Descriptions.Item>
              <Descriptions.Item label="指标">{report.indicatorCount}</Descriptions.Item>
              <Descriptions.Item label="术语/规则">{report.derivedRuleCount}</Descriptions.Item>
              <Descriptions.Item label="事实记录">{report.factCount}</Descriptions.Item>
              <Descriptions.Item label="问题样本">{report.questionCount}</Descriptions.Item>
              <Descriptions.Item label="日期范围" span={2}>
                {report.minDate || '-'} 至 {report.maxDate || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="文件校验码">
                <span className={styles.hashText}>{report.checksum}</span>
              </Descriptions.Item>
            </Descriptions>

            {report.errors?.length > 0 && (
              <Alert
                type="error"
                showIcon
                message={`发现 ${report.errors.length} 个问题，修正后重新校验`}
              />
            )}
            {report.errors?.length > 0 && (
              <Table
                size="small"
                rowKey={(record, index) => `${record.sheet}-${record.row}-${index}`}
                pagination={{ pageSize: 8 }}
                dataSource={report.errors}
                columns={[
                  { title: '工作表', dataIndex: 'sheet', width: 150 },
                  { title: '行', dataIndex: 'row', width: 70 },
                  { title: '列', dataIndex: 'column', width: 130 },
                  { title: '错误码', dataIndex: 'code', width: 160 },
                  { title: '说明', dataIndex: 'message' },
                ]}
              />
            )}
          </>
        )}

        {report?.success && !completed && (
          <Form
            form={form}
            layout="vertical"
            className={styles.importForm}
            initialValues={{
              modelId,
              dataSetName: '银行业智能问数数据集',
              dataSetBizName: 'bank_indicator_dataset',
              dateField: 'data_date',
              organizationField: 'organization_code',
              indicatorCodeField: 'metric_code',
              indicatorValueField: 'metric_value',
            }}
          >
            <Form.Item name="modelId" hidden>
              <Input />
            </Form.Item>
            <div className={styles.formGrid}>
              <Form.Item label="数据集名称" name="dataSetName" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item label="数据集业务名" name="dataSetBizName" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item label="日期字段" name="dateField" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item label="机构字段" name="organizationField" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item
                label="指标编码字段"
                name="indicatorCodeField"
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
              <Form.Item label="指标值字段" name="indicatorValueField" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </div>
            <Button
              type="primary"
              icon={<UploadOutlined />}
              loading={importing}
              disabled={!modelId}
              onClick={runImport}
            >
              导入指标与术语
            </Button>
          </Form>
        )}

        {completed && report && (
          <Result
            status="success"
            icon={<CheckCircleOutlined />}
            title="导入完成"
            subTitle={`创建：${counterText(report.created)}；更新：${counterText(
              report.updated,
            )}；跳过：${counterText(report.skipped)}`}
          />
        )}
      </div>
    </div>
  );
};

export default ImportPanel;
