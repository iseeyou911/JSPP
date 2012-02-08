import java.text.ParseException;

def confFile = (hasProperty('project')) ? project.properties['project.confFile'] : null;
def sourceDir = (hasProperty('project')) ? project.properties['project.sourceDir'] : null;
def excludeDir = (hasProperty('project')) ? project.properties['project.excludeDir'] : null;

for (a in this.args) {
	def pair = a =~/^([^=]*)=(.*)$/;
	if (pair.matches()) {
		def key = pair[0][1].trim();
		def value = pair[0][2].trim();
		
		if (confFile == null && key.equals('project.confFile')) {
			confFile = value;
		} else if (sourceDir == null && key.equals('project.sourceDir')) {
			sourceDir = value;
		} else if (excludeDir == null && key.equals('project.excludeDir')) {
			excludeDir = value.split(',');
		}
	}

}

if (sourceDir == null){
	throw new RuntimeException ("project.confFile not found");
}

if (excludeDir == null){
	excludeDir = [];
}

class Global {
	static vars = [:]
 }
Global.vars.closePattern = null;
Global.vars.state = null;


def checkNextLine = null;
def replaceTo = null;
def configFile = [:]
Global.vars.closePattern = null;
Global.vars.state = null;

if (confFile != null) {
	new File(confFile).readLines().each{
		def pair = it =~ /^([^:]*):(.*)$/;
		if (pair.matches()) {
			configFile.put(pair[0][1].trim(), pair[0][2].trim());
		}
	};
	println 'Configuration params:';
	println configFile;
} else {
	println 'Configuration file is\'t specify';
}



def checkCondition;
checkCondition = {condition, localParams ->
	def pair = condition =~ /^([^=]*)=(.*)$/;
	if (pair.matches()) {
		def key = pair[0][1].trim();
		def value = pair[0][2].trim();
		return configFile.get(key) == value || localParams.get(key) == value;
	}
	return configFile.get(condition) != null || localParams.get(condition) != null;
}

def patternMapper;

patternMapper = { matcher ->
	def keyWords = ['if', 'pattern', 'to', 'file', 'type'];
	def result = [:];
	for (int i = 1; i < matcher[0].size(); i++){
		def isNotKeyWord = true;
		keyWords.each { 
			if (matcher[0][i] == it){
				result.put(it, matcher[0][i + 1]);
				i++;
				isNotKeyWord = false;
				return true;
			}
		}
		if (isNotKeyWord && matcher[0][i] != null) {
			result.put('innerText', matcher[0][i]);
		}
	}
	
	return result;
}

def replacePlaceHolder;
replacePlaceHolder = { str, localParams ->
	str.replaceAll(/\$\{([^}]*)\}/, {full, word ->
		def pair = word =~ /^([^:]*):(.*)$/;
		if (pair.matches()) {
			word = pair[0][1]
		}

		
		def tmp = localParams.get(word);
		if (tmp == null){
			tmp = configFile.get(word);
		}
		
		if (tmp == null && hasProperty('project')){
			println project.properties[word]
			tmp = project.properties[word];
		}

		if (tmp != null) {
			if (pair.matches() && pair[0][2] == "removeQuotes") {
				tmp.replaceAll(/^['"](.*)['"]$/, '$1')
			} else if (pair.matches()) {
			throw new RuntimeException("Can't find command ${pair[0][2]}")
			} else {
				tmp
			}
		} else {
			throw new RuntimeException("Can't find placeholder ${full}")
		}
	})
}

/*
* Replace command
*/
def replace;
replace  =  { line, patternO, ml, localParams ->
   matcher = (line =~ patternO);
   def result;
   if(matcher.find()) {
	   def commands = patternMapper(matcher);
	   def strPattern = commands.get('pattern');
	   def strReplace = commands.get('to');
	   def condition = commands.get('if');
	   def oldText = commands.get('innerText');
	   if (condition != null && !checkCondition(condition, localParams)){
		   return null;
	   }
	   if (strReplace != null ) {
		   def str = replacePlaceHolder(strReplace.trim(), localParams);

		   result = matcher.replaceAll (str).trim()
	   } else {
		   result = matcher.replaceAll (oldText).trim()
	   }

	   if (ml){
		   Global.vars.state = "REPLACE_START";
	   }

	   if (strPattern != null || ml) {
		   return [strPattern, result]
	   } else {
		   return result;
	   }
   }
}

/*
* Insert command
*/
def insert;

insert = { line, patternO, ml, localParams ->
   matcher = (line =~ patternO);

   if(matcher.find()) {
	   def commands = patternMapper(matcher);
	   def condition = commands.get('if');
	   def oldText = commands.get('innerText');
	   
	   if (condition == null || checkCondition(condition, localParams)){
		   if (ml){
			   Global.vars.state = "INSERT_START";
		   }
		   return "";
	   }
   }
}

/*
* import command. If type is CSS we will put data to tag <style>
*/
def import1;

import1 = { line, patternO, ml, localParams ->
   matcher = (line =~ patternO);

   if(matcher.find()) {
	   def commands = patternMapper(matcher);
	   def condition = commands.get('if');
	   def file = commands.get('file');
	   def oldText = commands.get('innerText');
	   def type = commands.get('type');
	   
	   if (condition == null || checkCondition(condition, localParams)){
		   try {
			   def result = null
			   if (type == 'css'){
				   result = '<style type="text/css">' + (new File(replacePlaceHolder(file, localParams))).readLines().join() + '</style>';
			   } else {
			        result = (new File(replacePlaceHolder(file, localParams))).readLines().join('\n');
			   }
			   println "File ${file} was imported";
			   return result;
		   } catch (e){
		   		println 'Import css error';
		   		return "";
		   }
	   }
   }
}

/*
 * Define
 */
def define;

define = { line, patternO, ml, localParams ->
	matcher = (line =~ patternO);
	
	   if(matcher.find()) {
		   def commands = patternMapper(matcher);
		   def oldText = commands.get('innerText');
		   def pair = oldText.trim() =~ /^([^=]*)=(.*)$/;
		   if (pair.matches()) {
			   def key = pair[0][1].trim();
			   def value = pair[0][2].trim();
			   localParams.putAt(key, value);
		   } else {
		   	localParams.putAt(oldText.trim(), 'RUSSKIEVODKABALALAIKA!');
		   }
	   }
}

 def patterns = [

 	js : [
			 'import' : [
				 calll : import1,
				 single : ~/\/\/\s*import\s*(file)\s*=\s*\^([^\^]*)\^\s*(?:(type)\s*=\s*\^([^\^]*)\^\s*)?(?:(if)\s*=\s*\^([^\^]*)\^\s*)?/,
			 ],
		 	define : [
				 calll : define,
				 single : ~/\/\/\s*define\s*(.*)/,
			],
 			replace : [
 				calll : replace,
 				single : ~/\/\*\s*replace\s*(to)\s*=\s*\^([^\^]*)\^\s*(?:(pattern)\s*=\s*\^([^\^]*)\^\s*)?(?:(if)\s*=\s*\^([^\^]*)\^\s*)?\*\/(?:(.*)(?=\/\*\/\s*replace\s*\*\/)\/\*\/\s*replace\s*\*\/)/,
 				open : ~/\/\*\s*replace\s*(to)\s*=\s*\^([^\^]*)\^\s*(?:(pattern)\s*=\s*\^([^\^]*)\^\s*)?(?:(if)\s*=\s*\^([^\^]*)\^\s*)?\s*\*\/\s*/,
 				close : ~/\s*\/replace\s*\*\/\s*/
 			],
 			insert : [
 				calll : insert,
 				open : ~/\/\*\s*insert\s*(?:(if)\s*=\s*\^([^\^]*)\^\s*)?\s*/,
 				close : ~/\s*\/insert\s*\*\/\s*/
 			]
 		],

 	html : [
			define : [
				 calll : define,
				 single : ~/<!--\s*define\s*(.*)-->/,
			],
			'import' : [
				calll : import1,
				single : ~/<!--\s*import\s*(file)\s*=\s*\^([^\^]*)\^\s*(?:(type)\s*=\s*\^([^\^]*)\^\s*)?(?:(if)\s*=\s*\^([^\^]*)\^\s*)?-->/,
			],
 			replace : [
 				calll : replace,
 				open : ~/<!--\s*replace\s*(to)\s*=\s*\^([^\^]*)\^\s*(?:(pattern)\s*=\s*\^([^\^]*)\^\s*)?(?:(if)\s*=\s*\^([^\^]*)\^\s*)?-->/,
 				close : ~/<!--\s*\/replace\s*-->/
 			],
 			insert : [
 				calll : insert,
 				open : ~/<!--\s*insert\s*(?:(if)\s*=\s*\^([^\^]*)\^\s*)?\s*/,
 				close : ~/\s*\/insert\s*-->\s*/
 			]
 		]

 ]

patterns['json'] = patterns['js'];
patterns['xhtml'] = patterns['html'];
def editClos = null;
editClos = {
//println "Dir ${it.canonicalPath}";
	def matcher = it.canonicalPath =~ /.*\\(.*)$/
	
	def stringWasReplaced = false;

		if (matcher.find() && excludeDir.findAll{w -> w == matcher[0][1]}.size() == 0)
		{
			it.eachDir ( editClos );
			it.eachFileMatch(~/.*\.(?:html|xhtml|js|json)/) {
			f ->
				//println "Reading file ${f.path}"
				def editedLines = 0;
				def lines = f.readLines();
				def newFile = [];
				def localParams = [:];
				for (line in lines) {
					def ext = (f.path =~ /\.([^.]*)$/);
					def result = null;

					if (Global.vars.state == null){
						def cPatterns = patterns[ext[0][1]];
						if (cPatterns != null) {

							Global.vars.closePattern = null;
							cPatterns.each{
								if (result == null  && it.value['single'] != null){
									result = it.value['calll'] (line, it.value['single'], false, localParams);
								}
								if (result == null && it.value['open'] != null){
									result = it.value['calll'] (line, it.value['open'], true, localParams);
									Global.vars.closePattern = it.value['close'];
								}
							}
						}

						if (result instanceof ArrayList) {
							checkNextLine = result[0];
							replaceTo = result[1];
							result = "";
						}
					} else {

						if (Global.vars.state != null && checkEnd(line, Global.vars.closePattern)) {
							Global.vars.state = null;
							checkNextLine = null;
							stringWasReplaced = false;
							line = "";
						} else {
							if (Global.vars.state == "REPLACE_START") {
								if(checkNextLine != null) {
									result = line.replace(checkNextLine, replaceTo);
								} else if (!stringWasReplaced){
									result = replaceTo;
									stringWasReplaced = true;
								} else {
								    result = "";
								}

								println result;
							}

							if (Global.vars.state == "INSERT_START" && result == null) {
								result = line;
								println result;
							}
						}
					}

					if (result != null) {
						editedLines++;
						newFile << result;
					} else {
						newFile << line;
					}
				}
				if (editedLines > 0) {
					f.write(newFile.join("\n"), "UTF-8")
				}
			}
		}
	}


def checkEnd (line, patternC) {
	matcher = (line =~ patternC);
	if(matcher.find()) {
		def strInsert = matcher[0][1];

		if (strInsert != null) {
			return true;
		}
	}
	return false;
}

// Apply closure
editClos( new File(sourceDir) )

