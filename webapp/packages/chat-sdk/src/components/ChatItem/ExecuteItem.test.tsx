import { render, screen } from '@testing-library/react';
import { MsgDataType } from '../../common/type';
import ExecuteItem from './ExecuteItem';

jest.mock('../ChatMsg', () => () => <div data-testid="structured-result" />);
jest.mock('./BusinessInsightPanel', () => () => <div data-testid="business-insight" />);
jest.mock('react-syntax-highlighter', () => ({
  Prism: () => <pre data-testid="syntax-highlighter" />,
}));
jest.mock('react-syntax-highlighter/dist/esm/styles/prism', () => ({
  solarizedlight: {},
}));
jest.mock('react-markdown', () => ({ children }: { children?: React.ReactNode }) => <>{children}</>);

const plainTextResult = {
  queryMode: 'PLAIN_TEXT',
  queryState: 'SUCCESS',
  textResult: '这是不经过 SQL 的直接回答。',
} as MsgDataType;

describe('ExecuteItem', () => {
  it('routes PLAIN_TEXT results without table data to the direct answer card', () => {
    render(
      <ExecuteItem
        chartIndex={0}
        executeLoading={false}
        question="你好"
        data={plainTextResult}
      />
    );

    expect(screen.getByRole('article', { name: '纯文本回答' }).textContent).toBe(
      '这是不经过 SQL 的直接回答。'
    );
    expect(screen.queryByTestId('structured-result')).toBeNull();
    expect(screen.queryByTestId('business-insight')).toBeNull();
  });
});
