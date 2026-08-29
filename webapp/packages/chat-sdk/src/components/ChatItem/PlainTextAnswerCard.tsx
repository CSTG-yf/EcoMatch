import React from 'react';
import ReactMarkdown from 'react-markdown';
import { PREFIX_CLS } from '../../common/constants';

type Props = {
  text?: string;
};

const PlainTextAnswerCard: React.FC<Props> = ({ text }) => {
  const prefixCls = `${PREFIX_CLS}-item`;
  const hasText = typeof text === 'string' && text.trim().length > 0;

  return (
    <article className={`${prefixCls}-plain-text-answer-card`} aria-label="纯文本回答">
      {hasText ? <ReactMarkdown>{text}</ReactMarkdown> : '暂无回答'}
    </article>
  );
};

export default PlainTextAnswerCard;
