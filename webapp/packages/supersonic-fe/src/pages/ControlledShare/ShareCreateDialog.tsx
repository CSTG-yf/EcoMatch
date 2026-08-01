import {
  Alert,
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Typography,
  message,
} from 'antd';
import { CopyOutlined, LinkOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import React, { useEffect, useMemo, useState } from 'react';
import { buildControlledShareUrl, buildShareCreateRequest } from './model';
import { createShare } from './service';
import { ShareCreateValues, ShareIdentityPolicy } from './types';
import styles from './style.less';

type FormValues = Omit<ShareCreateValues, 'expiresAt'> & { expiresAt: Dayjs };

export type ShareCreateDialogProps = {
  open: boolean;
  dashboardId: number;
  dashboardName?: string;
  shareRouteBase?: string;
  onClose: () => void;
  onCreated?: () => void;
};

const errorMessage = (error: any) =>
  String(error?.msg || error?.message || error?.data?.msg || '创建分享失败');

export const ShareCreateDialog: React.FC<ShareCreateDialogProps> = ({
  open,
  dashboardId,
  dashboardName,
  shareRouteBase,
  onClose,
  onCreated,
}) => {
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [createdLink, setCreatedLink] = useState('');
  const identityPolicy = Form.useWatch('identityPolicy', form) as ShareIdentityPolicy | undefined;

  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        identityPolicy: 'AUTHENTICATED',
        allowedUsers: [],
        expiresAt: dayjs().add(7, 'day'),
        watermarkEnabled: true,
      });
      setCreatedLink('');
    }
  }, [form, open]);

  const title = useMemo(
    () => (dashboardName ? `分享「${dashboardName}」` : '创建受控分享'),
    [dashboardName],
  );

  const close = () => {
    setCreatedLink('');
    form.resetFields();
    onClose();
  };

  const submit = async () => {
    try {
      const values = await form.validateFields();
      const request = buildShareCreateRequest(dashboardId, values);
      setSubmitting(true);
      const created = await createShare(request);
      if (!created.token) {
        throw new Error('服务端未返回一次性分享 Token');
      }
      setCreatedLink(
        buildControlledShareUrl(
          created.token,
          window.location.origin,
          shareRouteBase || '/webapp/share',
        ),
      );
      onCreated?.();
    } catch (error: any) {
      if (!error?.errorFields) {
        message.error(errorMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const copyLink = async () => {
    if (!createdLink) {
      return;
    }
    try {
      await navigator.clipboard.writeText(createdLink);
      message.success('分享链接已复制');
    } catch {
      message.error('复制失败，请手动复制链接');
    }
  };

  return (
    <Modal
      open={open}
      title={title}
      width={600}
      onCancel={close}
      footer={
        createdLink
          ? [
              <Button key="close" type="primary" onClick={close}>
                完成
              </Button>,
            ]
          : undefined
      }
      okText="创建分享"
      cancelText="取消"
      confirmLoading={submitting}
      onOk={submit}
      destroyOnClose
    >
      {createdLink ? (
        <div className={styles.createdResult}>
          <Alert
            type="success"
            showIcon
            message="分享已创建"
            description="此链接仅在本次创建后可获取，关闭窗口后将无法再次查看。"
          />
          <Input.Group compact className={styles.copyField}>
            <Input
              aria-label="分享链接"
              value={createdLink}
              readOnly
              prefix={<LinkOutlined />}
              className={styles.copyInput}
            />
            <Button icon={<CopyOutlined />} onClick={copyLink} title="复制分享链接">
              复制
            </Button>
          </Input.Group>
        </div>
      ) : (
        <Form<FormValues>
          form={form}
          layout="vertical"
          requiredMark="optional"
          initialValues={{
            identityPolicy: 'AUTHENTICATED',
            allowedUsers: [],
            expiresAt: dayjs().add(7, 'day'),
            watermarkEnabled: true,
          }}
        >
          <Form.Item
            name="identityPolicy"
            label="访问身份"
            rules={[{ required: true, message: '请选择访问身份' }]}
          >
            <Select
              options={[
                { label: '已登录用户', value: 'AUTHENTICATED' },
                { label: '同机构用户', value: 'ORGANIZATION' },
                { label: '指定用户', value: 'USERS' },
              ]}
            />
          </Form.Item>

          {identityPolicy === 'USERS' && (
            <Form.Item
              name="allowedUsers"
              label="允许访问的用户"
              rules={[
                { required: true, type: 'array', min: 1, message: '至少指定一个用户' },
                {
                  validator: (_, users: string[]) =>
                    (users || []).length <= 100
                      ? Promise.resolve()
                      : Promise.reject(new Error('最多允许 100 个用户')),
                },
              ]}
            >
              <Select
                mode="tags"
                tokenSeparators={[',', '，', ';', '；']}
                maxTagCount="responsive"
                placeholder="输入用户标识后按回车"
                open={false}
              />
            </Form.Item>
          )}

          <div className={styles.formGrid}>
            <Form.Item
              name="expiresAt"
              label="有效期至"
              rules={[{ required: true, message: '请选择有效期' }]}
            >
              <DatePicker
                showTime
                format="YYYY-MM-DD HH:mm"
                disabledDate={(current) =>
                  current.valueOf() < dayjs().startOf('day').valueOf() ||
                  current.valueOf() > dayjs().add(30, 'day').endOf('day').valueOf()
                }
                className={styles.fullWidth}
              />
            </Form.Item>
            <Form.Item name="maxAccessCount" label="访问次数上限">
              <InputNumber
                min={1}
                max={100000}
                precision={0}
                placeholder="不限制"
                className={styles.fullWidth}
              />
            </Form.Item>
          </div>

          <Form.Item name="watermarkEnabled" label="水印" valuePropName="checked">
            <Switch checkedChildren="开启" unCheckedChildren="关闭" />
          </Form.Item>
          <Typography.Text type="secondary">
            访问者仍需通过服务端身份、对象权限和数据权限校验。
          </Typography.Text>
        </Form>
      )}
    </Modal>
  );
};

export default ShareCreateDialog;
