/** 助理头像：使用品牌物料里的蓝色简约机器人（supersonic-fe/public/branding） */
const AssistantAvatar: React.FC<{ size?: number; className?: string }> = ({
  size = 32,
  className,
}) => (
  <img
    className={className}
    src="/webapp/branding/bank-query-avatar.svg"
    alt="智能助理"
    width={size}
    height={size}
    style={{ borderRadius: Math.round(size / 3.5), display: 'inline-block' }}
  />
);

export default AssistantAvatar;
