import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { message, Button, Space, Popconfirm, Modal, Select } from 'antd';
import React, { useRef, useState, useEffect } from 'react';
import { StatusEnum } from '../../enum';
import { useModel } from '@umijs/max';
import {
  deleteView,
  updateView,
  getDataSetList,
  getAllModelByDomainId,
  importBankSemanticResources,
} from '../../service';
import ViewCreateFormModal from './ViewCreateFormModal';
import moment from 'moment';
import styles from '../../components/style.less';
import { ISemantic } from '../../data';
import { ColumnsConfig } from '../../components/TableColumnRender';
import ViewSearchFormModal from './ViewSearchFormModal';
import { toDatasetEditPage } from '@/pages/SemanticModel/utils';

type Props = {
  // dataSetList: ISemantic.IDatasetItem[];
  disabledEdit?: boolean;
};

const DataSetTable: React.FC<Props> = ({ disabledEdit = false }) => {
  const domainModel = useModel('SemanticModel.domainData');
  const { selectDomainId } = domainModel;

  const [viewItem, setViewItem] = useState<ISemantic.IDatasetItem>();
  const [saveLoading, setSaveLoading] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(false);
  const [createDataSourceModalOpen, setCreateDataSourceModalOpen] = useState(false);
  const [searchModalOpen, setSearchModalOpen] = useState(false);
  const [modelList, setModelList] = useState<ISemantic.IModelItem[]>([]);
  const actionRef = useRef<ActionType>();
  const [editFormStep, setEditFormStep] = useState<number>(0);
  const [bankImportOpen, setBankImportOpen] = useState(false);
  const [bankImportFile, setBankImportFile] = useState<File>();
  const [bankImportModelId, setBankImportModelId] = useState<number>();
  const [bankImportLoading, setBankImportLoading] = useState(false);

  const updateViewStatus = async (modelData: ISemantic.IDatasetItem) => {
    setSaveLoading(true);
    const { code, msg } = await updateView({
      ...modelData,
    });
    setSaveLoading(false);
    if (code === 200) {
      queryDataSetList();
    } else {
      message.error(msg);
    }
  };

  const [viewList, setViewList] = useState<ISemantic.IDatasetItem[]>();

  useEffect(() => {
    if (!selectDomainId) {
      return;
    }
    queryDataSetList();
    queryDomainAllModel();
  }, [selectDomainId]);

  const queryDataSetList = async () => {
    setLoading(true);
    const { code, data, msg } = await getDataSetList(selectDomainId);
    setLoading(false);
    if (code === 200) {
      setViewList(data);
    } else {
      message.error(msg);
    }
  };

  const queryDomainAllModel = async () => {
    const { code, data, msg } = await getAllModelByDomainId(selectDomainId);
    if (code === 200) {
      setModelList(data);
    } else {
      message.error(msg);
    }
  };

  const openBankImport = () => {
    const preferredModel =
      modelList.find((item) => item.bizName === 'bank_metric_daily') || modelList[0];
    setBankImportModelId(preferredModel?.id);
    setBankImportFile(undefined);
    setBankImportOpen(true);
  };

  const handleBankImport = async () => {
    if (!bankImportModelId) {
      message.error('请选择目标模型');
      return;
    }
    if (!bankImportFile) {
      message.error('请选择银行指标工作簿');
      return;
    }

    const formData = new FormData();
    formData.append('file', bankImportFile);
    formData.append('modelId', String(bankImportModelId));
    formData.append('dataSetName', '银行日指标数据集');
    formData.append('dataSetBizName', 'bank_daily_metrics');
    formData.append('dateField', 'data_date');
    formData.append('organizationField', 'org_code');
    formData.append('indicatorCodeField', 'metric_code');
    formData.append('indicatorValueField', 'metric_value');

    setBankImportLoading(true);
    try {
      const { code, data: report, msg } = await importBankSemanticResources(formData);
      if (code === 200 && report?.success) {
        const metricCount = report?.created?.metrics || report?.updated?.metrics || 0;
        message.success(`银行语义资源导入成功，已处理 ${metricCount} 个指标`);
        setBankImportOpen(false);
        queryDataSetList();
      } else {
        message.error(report?.errors?.[0]?.message || msg || '银行语义资源导入失败');
      }
    } catch (error) {
      message.error('银行语义资源导入请求失败');
    } finally {
      setBankImportLoading(false);
    }
  };

  const columnsConfig = ColumnsConfig();

  const columns: ProColumns[] = [
    {
      dataIndex: 'id',
      title: 'ID',
      width: 80,
      search: false,
    },
    {
      dataIndex: 'name',
      title: '数据集名称',
      search: false,
      render: (name, record) => {
        return (
          <a
            onClick={() => {
              toDatasetEditPage(record.domainId, record.id, 'relation');
              // setEditFormStep(1);
              // setViewItem(record);
              // setCreateDataSourceModalOpen(true);
            }}
          >
            {name}
          </a>
        );
      },
    },
    {
      dataIndex: 'bizName',
      title: '英文名称',
      search: false,
    },
    {
      dataIndex: 'status',
      title: '状态',
      search: false,
      render: columnsConfig.state.render,
    },
    {
      dataIndex: 'createdBy',
      title: '创建人',
      search: false,
    },
    {
      dataIndex: 'description',
      title: '描述',
      search: false,
    },
    {
      dataIndex: 'updatedAt',
      title: '更新时间',
      search: false,
      render: (value: any) => {
        return value && value !== '-' ? moment(value).format('YYYY-MM-DD HH:mm:ss') : '-';
      },
    },
  ];

  if (!disabledEdit) {
    columns.push({
      title: '操作',
      dataIndex: 'x',
      valueType: 'option',
      width: 250,
      render: (_, record) => {
        return (
          <Space className={styles.ctrlBtnContainer}>
            <a
              key="metricEditBtn"
              onClick={() => {
                toDatasetEditPage(record.domainId, record.id);
                // setEditFormStep(0);
                // setViewItem(record);
                // setCreateDataSourceModalOpen(true);
              }}
            >
              编辑
            </a>
            <a
              key="searchEditBtn"
              onClick={() => {
                setViewItem(record);
                setSearchModalOpen(true);
              }}
            >
              查询设置
            </a>
            {record.status === StatusEnum.ONLINE ? (
              <Button
                type="link"
                key="editStatusOfflineBtn"
                onClick={() => {
                  updateViewStatus({
                    ...record,
                    status: StatusEnum.OFFLINE,
                  });
                }}
              >
                停用
              </Button>
            ) : (
              <Button
                type="link"
                key="editStatusOnlineBtn"
                onClick={() => {
                  updateViewStatus({
                    ...record,
                    status: StatusEnum.ONLINE,
                  });
                }}
              >
                启用
              </Button>
            )}
            <Popconfirm
              title="确认删除？"
              okText="是"
              cancelText="否"
              onConfirm={async () => {
                const { code, msg } = await deleteView(record.id);
                if (code === 200) {
                  queryDataSetList();
                } else {
                  message.error(msg);
                }
              }}
            >
              <a key="modelDeleteBtn">删除</a>
            </Popconfirm>
          </Space>
        );
      },
    });
  }

  return (
    <>
      <ProTable
        className={`${styles.classTable} ${styles.classTableSelectColumnAlignLeft}`}
        actionRef={actionRef}
        rowKey="id"
        search={false}
        columns={columns}
        loading={loading}
        dataSource={viewList}
        tableAlertRender={() => {
          return false;
        }}
        size="small"
        options={{ reload: false, density: false, fullScreen: false }}
        toolBarRender={() =>
          disabledEdit
            ? [<></>]
            : [
                <Button key="bankImport" disabled={!modelList.length} onClick={openBankImport}>
                  导入银行指标工作簿
                </Button>,
                <Button
                  key="create"
                  type="primary"
                  onClick={() => {
                    setViewItem(undefined);
                    setCreateDataSourceModalOpen(true);
                  }}
                >
                  创建数据集
                </Button>,
              ]
        }
      />
      {createDataSourceModalOpen && (
        <ViewCreateFormModal
          step={editFormStep}
          domainId={selectDomainId}
          viewItem={viewItem}
          modelList={modelList}
          onSubmit={() => {
            queryDataSetList();
            setCreateDataSourceModalOpen(false);
          }}
          onCancel={() => {
            setCreateDataSourceModalOpen(false);
          }}
        />
      )}

      {searchModalOpen && (
        <ViewSearchFormModal
          domainId={selectDomainId}
          viewItem={viewItem}
          onSubmit={() => {
            queryDataSetList();
            setSearchModalOpen(false);
          }}
          onCancel={() => {
            setSearchModalOpen(false);
          }}
        />
      )}

      {bankImportOpen && (
        <Modal
          destroyOnHidden
          title="导入银行指标工作簿"
          open={true}
          confirmLoading={bankImportLoading}
          okText="开始导入"
          cancelText="取消"
          onOk={handleBankImport}
          onCancel={() => setBankImportOpen(false)}
        >
          <p>导入后会更新银行日指标数据集，并同步机构、指标、术语和指标字典。</p>
          <div style={{ marginBottom: 16 }}>
            <label htmlFor="bank-import-model">目标模型</label>
            <Select
              id="bank-import-model"
              aria-label="目标模型"
              style={{ display: 'block', marginTop: 8, width: '100%' }}
              value={bankImportModelId}
              options={modelList.map((item) => ({ label: item.name, value: item.id }))}
              onChange={setBankImportModelId}
            />
          </div>
          <div>
            <label htmlFor="bank-import-workbook">银行指标工作簿（.xlsx）</label>
            <input
              id="bank-import-workbook"
              aria-label="银行指标工作簿（.xlsx）"
              style={{ display: 'block', marginTop: 8 }}
              type="file"
              accept=".xlsx"
              onChange={(event) => setBankImportFile(event.target.files?.[0])}
            />
          </div>
        </Modal>
      )}
    </>
  );
};
export default DataSetTable;
