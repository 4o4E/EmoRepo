package top.e404.emorepo.experiment.lsposed

/** 导入选择只显示真实可写包，并稳定地把折叠包移到末尾。 */
internal fun importTargetPacks(packs: List<PanelPack>): List<PanelPack> {
    val writable = packs.filter(PanelPack::writable)
    return writable.filterNot(PanelPack::collapsed) + writable.filter(PanelPack::collapsed)
}
