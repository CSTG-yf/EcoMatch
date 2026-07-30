import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Space } from 'antd';

const AttributeConditionEditor: React.FC = () => (
  <Form.List name="attributeConditionEntries">
    {(fields, { add, remove }) => (
      <Space direction="vertical" style={{ width: '100%' }} size={8}>
        {fields.map(({ key, name, ...restField }) => (
          <Space key={key} align="baseline" style={{ display: 'flex' }}>
            <Form.Item
              {...restField}
              name={[name, 'key']}
              rules={[{ required: true, message: '请输入属性名' }]}
              style={{ marginBottom: 0 }}
            >
              <Input placeholder="属性名，如 position" />
            </Form.Item>
            <Form.Item
              {...restField}
              name={[name, 'value']}
              rules={[{ required: true, message: '请输入属性值' }]}
              style={{ marginBottom: 0 }}
            >
              <Input placeholder="属性值，如 branch_manager" />
            </Form.Item>
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              aria-label="删除属性条件"
              onClick={() => remove(name)}
            />
          </Space>
        ))}
        <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()} block>
          添加属性条件
        </Button>
      </Space>
    )}
  </Form.List>
);

export default AttributeConditionEditor;
