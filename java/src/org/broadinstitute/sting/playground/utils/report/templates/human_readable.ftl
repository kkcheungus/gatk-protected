<#-- a couple of basic fields we use--> 
<#assign colWidth=20>
<#assign colTextWidth=17>
<#assign currentAnalysis="">

Report:			${root.value}
Description:	${root.description}

<#-- get any tags on the root node (any information we've tagged the report with) -->
<#list root.children as tagNode>
	<#if tagNode.tag>
Tag:			${tagNode.name}	= ${tagNode.value}
	</#if>
</#list>

<#list root.childresn as analysis>
	<#if analysis.complex>
		<#if analysis.value!=currentAnalysi>
		<#assign currentAnalysis=analysis.value>

Analysis:		${analysis.value} 

Column names specific to the analysis:
			<@emit_column_names_with_descriptions analysis=analysis/>


		    <@emit_tables analysis=analysis/>

			<@emit_tags analysis=analysis/>
			<@emit_column_names analysis=analysis/>
					
----------------------------------------------------------------------------------------------------------------------------------------			
		</#if>
		<@emit_row_values analysis=analysis/>
	</#if>
</#list>
<#-- -------------------- -->
<#-- emit only tables     -->
<#macro emit_tables analysis>
    <#if analysis.complex && !analysis.tag>
		<#list analysis.children as child>
		    <#if child.table>
		        <@emit_tags analysis=analysis/>
	            <#list child.tableRows as rows>
            		<@emit_tag_values analysis=analysis/>

----------------------------------------------------------------------------------------------------------------------------------------            		
            		<#list rows as node>
            			<@emit_name value=node.value/>            			
            		</#list>

            	</#list>
            </#if>
        </#list>
    </#if>
</#macro>
<#-- -------------------- -->
<#-- get the data tag values -->
<#macro emit_row_values analysis>
	<#list analysis.tableRowsNoTables as rows>
		<@emit_tag_values analysis=analysis/>
		<#list rows as node>			
			<@emit_name value=node.value/>	
		</#list>
		
	</#list>	
</#macro>
<#-- -------------------- -->
<#-- get the column names -->
<#macro emit_column_names analysis>
	<#if analysis.complex && !analysis.tag>
		<#list analysis.children as child>
			<#if child.complex && !child.table>
				<@emit_name value=child.value/>			
			</#if>				
		</#list>
	</#if>
</#macro>
<#-- -------------------- -->
<#-- get the column names to display at the top of the analysis -->
<#macro emit_column_names_with_descriptions analysis>
	<#if analysis.complex && !analysis.tag>
		<#list analysis.children as child>
			<#if child.complex && !child.table>
				${child.value?right_pad(40)}${child.description}				
			</#if>
		</#list>
	</#if>
</#macro>
<#-- -------------------- -->
<#-- get the tag values -->
<#macro emit_tag_values analysis>
	<#list analysis.children as child>
		<#if child.tag>
			<@emit_name value=child.value/>		
		</#if>
	</#list>
</#macro>
<#-- -------------------- -->
<#-- get the tag names -->
<#macro emit_tags analysis>
	<#list analysis.children as child>
		<#if child.tag>
			<@emit_name value=child.name/>		
		</#if>
	</#list>
</#macro>

<#-- -------------------- -->
<#-- a macro for formatting emitted names -->
<#macro emit_name value>
	<#if (value?length > colTextWidth)>
		<#lt>${(value?substring(0, colTextWidth)+"..")?right_pad(colWidth)}<#rt>
	<#else>
		<#lt>${value?right_pad(colWidth)}<#rt>
	</#if>
</#macro>
