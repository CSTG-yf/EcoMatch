import React from 'react';
import { render, screen } from '@testing-library/react';
import PlainTextAnswerCard from './PlainTextAnswerCard';

jest.mock('react-markdown', () => ({ children }: { children?: React.ReactNode }) => (
  <div data-testid="markdown-content">{children}</div>
));

describe('PlainTextAnswerCard', () => {
  it('renders the direct answer text', () => {
    render(<PlainTextAnswerCard text="这是直接回答。" />);

    expect(screen.getByRole('article', { name: '纯文本回答' }).textContent).toBe('这是直接回答。');
  });

  it('preserves multiline answer text', () => {
    render(<PlainTextAnswerCard text={'第一行\n第二行'} />);

    expect(screen.getByRole('article', { name: '纯文本回答' }).textContent).toBe('第一行\n第二行');
  });

  it('shows a fallback when the answer is empty', () => {
    render(<PlainTextAnswerCard text="   " />);

    expect(screen.getByRole('article', { name: '纯文本回答' }).textContent).toBe('暂无回答');
  });

  it('renders HTML-looking content as text', () => {
    render(<PlainTextAnswerCard text="<strong>不是 HTML</strong>" />);

    const card = screen.getByRole('article', { name: '纯文本回答' });
    expect(card.textContent).toBe('<strong>不是 HTML</strong>');
    expect(card.querySelector('strong')).toBeNull();
  });
});
