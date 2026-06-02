import type { FC } from 'react';

interface SkeletonProps {
  className?: string;
}

const Skeleton: FC<SkeletonProps> = ({ className }) => {
  return (
    <div className={`skeleton-shimmer ${className}`} />
  );
};

export default Skeleton;
