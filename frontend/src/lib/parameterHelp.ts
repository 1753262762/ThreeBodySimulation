/**
 * 参数帮助元数据。
 *
 * 集中维护参数含义、主要影响、范围/示例，供 Tooltip 与表单动态展示，
 * 不在模板中散落长文案。示例与范围随当前单位制（SI/天文单位）变化，
 * 但不会把天文单位写入任何权威状态。
 */
import { unitLabel, type UnitSystem } from './units'

export type ParameterKind =
  | 'mass'
  | 'position'
  | 'velocity'
  | 'timeStep'
  | 'softeningLength'
  | 'totalTime'
  | 'maxSteps'
  | 'gravitationalConstant'

export interface ParameterHelp {
  /** 参数含义。 */
  meaning: string
  /** 主要影响。 */
  impact: string
  /** 范围或示例（随单位制变化）。 */
  range: string
  /** 可选的默认示例值（随单位制变化）。 */
  example?: string
}

function exampleMass(system: UnitSystem): string {
  return system === 'SI' ? '例如 1.98892e30 kg（1 M☉）' : '例如 1 M☉'
}

function exampleTime(system: UnitSystem): string {
  return `例如 1 ${unitLabel('time', system)}`
}

export function getParameterHelp(kind: ParameterKind, system: UnitSystem): ParameterHelp {
  const lengthUnit = unitLabel('length', system)
  const velocityUnit = unitLabel('velocity', system)
  switch (kind) {
    case 'mass':
      return {
        meaning: '天体所含物质的惯性与引力尺度。',
        impact: '增大会增强对其他天体的引力，并改变系统质心位置。',
        range: `必须大于 0；${exampleMass(system)}。`,
        example: exampleMass(system),
      }
    case 'position':
      return {
        meaning: '模拟时间 0 时天体相对坐标原点的三维坐标。',
        impact: '决定天体初始间距、引力方向与近遇风险。',
        range: `单位随偏好为 ${unitLabel('length', 'SI')} 或 ${lengthUnit}；完全重合会被阻止。`,
      }
    case 'velocity':
      return {
        meaning: '模拟时间 0 时天体的三维速度向量。',
        impact: '方向与大小决定系统束缚、逃逸倾向与轨迹形状。',
        range: `单位为 ${unitLabel('velocity', 'SI')} 或 ${velocityUnit}；高于逃逸尺度可能快速飞离。`,
      }
    case 'timeStep':
      return {
        meaning: 'RK4 积分每次推进的模拟时间。',
        impact: '越小通常越稳定但计算步数越多；过大会漏过快速变化。',
        range: `必须大于 0；服务端会按局部周期与单步位移给出风险提示。${exampleTime(system)}。`,
        example: exampleTime(system),
      }
    case 'softeningLength':
      return {
        meaning: '用于平滑近距离引力奇点的软化长度 ε。',
        impact: '太小近接保护不足，太大会抹平真实的近距离引力作用。',
        range: '必须非负；ε/rMin 的参考区间为 1e-4～1e-2。',
      }
    case 'totalTime':
      return {
        meaning: '目标结束模拟时间。',
        impact: '不改变瞬时物理，只决定覆盖时段、积分步数与存储量。',
        range: `必须大于 0，后端上限 1e14 s。${exampleTime(system)}。`,
        example: exampleTime(system),
      }
    case 'maxSteps':
      return {
        meaning: '最多执行的积分步数量。',
        impact: '限制计算时间；与目标时间同时存在时按先达到者结束。',
        range: '1～100,000,000。',
      }
    case 'gravitationalConstant':
      return {
        meaning: '牛顿引力强度常数 G。',
        impact: '改变全部天体之间的引力作用尺度。',
        range: '通常保持 6.6743e-11 N·m²/kg²。',
      }
  }
}
